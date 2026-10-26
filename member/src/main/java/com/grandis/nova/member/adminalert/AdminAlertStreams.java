package com.grandis.nova.member.adminalert;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.member.adminalert.AdminAlertRules.Audience;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 이 인스턴스에 열린 관리자 알림 실시간 연결. 회원 알림의 실시간 연결을 따르되 둘이 다르다 — 안 읽은 수만이 아니라 바뀐 항목 자체를 보내므로 가장 새 값
 * 하나가 아니라 사건을 차례대로 쌓아 쓰고, 너무 쌓이면(maxPending) 닫는다. 한 관리자 계정(토큰 sub)의 연결이 maxPerPrincipal 을 넘으면 가장 오래된
 * 것에 event: replaced 를 쓴 뒤 닫는다(바꿔 끼우는 것이라 인스턴스 상한에 걸려도 받는다) — 화면이 사유 있는 종료로 알고 다시 연결하지 않게.
 *
 * 연결은 DB 를 읽지 않고 등록만 한다. 첫 unread 는 {@link AdminAlertTail} 이 다음 주기에 보낸다. 쓰기 · 닫기는 연결마다 가상 스레드에서 하고,
 * 목록 잠금을 쥔 채로는 쓰지도 닫지도 않는다. 주기 신호는 전용 스레드가 돌리고, 종료는 웹 서버의 정상 종료보다 먼저 한다(화면은 새 태스크로 다시 붙는다).
 */
@Component
@EnableConfigurationProperties(AdminAlertStreamProperties.class)
class AdminAlertStreams implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AdminAlertStreams.class);

    static final String METRIC = "member.admin-alert.streams";
    /**
     * 서버가 연결을 닫은 까닭(연결마다 한 번). replaced = 같은 계정의 연결이 상한을 넘어 가장 오래된 것을 닫았다, overflow = 쓰지 못한 사건이
     * maxPending 을 넘었다, disconnected = 쓰다 IOException(떠난 클라이언트), error = 쓰다 예기치 않은 예외(버그일 수 있다). 수명 · 토큰 만료 · 종료 ·
     * 이미 닫힌 연결은 세지 않는다
     */
    static final String CLOSED_METRIC = "member.admin-alert.stream.closed";
    static final String NOTIFICATION_EVENT = "notification";
    static final String UNREAD_EVENT = "unread";
    static final String REPLACED_EVENT = "replaced";
    static final String RESYNC_EVENT = "resync";

    private final AdminAlertStreamProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final ThreadFactory writers = Thread.ofVirtual().name("admin-alert-stream-write").factory();

    private final Object lock = new Object();
    /** lock 으로 지킨다. 연 순서대로 */
    private final List<Connection> connections = new ArrayList<>();
    private boolean closed;

    private ScheduledExecutorService heartbeats;
    private volatile boolean running;

    AdminAlertStreams(AdminAlertStreamProperties properties, Clock clock, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        Gauge.builder(METRIC, this, AdminAlertStreams::size).register(meterRegistry);
    }

    /**
     * @param principal      연결을 연 토큰의 sub. 같은 값의 연결끼리 maxPerPrincipal 을 센다
     * @param audience       이 탭만 받는다. null 이면 둘 다
     * @param tokenExpiresAt 연결을 연 액세스 토큰의 만료. 연결이 이보다 오래 살지 않는다
     * @throws BusinessException 503 — 연결이 가득 찼거나 종료 중이다
     */
    SseEmitter open(String principal, Audience audience, Instant tokenExpiresAt) {
        return register(principal, audience, new SseEmitter(lifetime(tokenExpiresAt).toMillis())).emitter;
    }

    Connection register(String principal, Audience audience, SseEmitter emitter) {
        Connection connection = new Connection(principal, audience, emitter);
        emitter.onCompletion(() -> forget(connection));
        emitter.onError(e -> forget(connection));
        // 시간이 다 되면 이 콜백 안에서 닫는다. 그냥 두면 MVC 가 이미 나간 스트림 위에 503 을 쓰려 한다
        emitter.onTimeout(connection::completeNow);
        Connection replaced = null;
        synchronized (lock) {
            if (closed) {
                throw unavailable("종료 중");
            }
            List<Connection> mine = connections.stream().filter(c -> c.principal.equals(principal)).toList();
            if (mine.size() >= properties.maxPerPrincipal()) {
                replaced = mine.getFirst();
                connections.remove(replaced);
            } else if (connections.size() >= properties.maxConnections()) {
                throw unavailable("연결 " + connections.size() + "개로 가득 찼다");
            }
            connections.add(connection);
        }
        if (replaced != null) {
            replaced.replace();
        }
        return connection;
    }

    /** 최대 수명과 토큰이 남은 시간 중 짧은 쪽. 0 이하를 넘기면 서블릿은 "만료 없음" 으로 읽어 최소 1ms 로 둔다. */
    Duration lifetime(Instant tokenExpiresAt) {
        Duration max = properties.maxLifetime();
        if (tokenExpiresAt == null) {
            return max;
        }
        Duration left = Duration.between(clock.instant(), tokenExpiresAt);
        if (left.compareTo(max) >= 0) {
            return max;
        }
        return left.isPositive() ? left : Duration.ofMillis(1);
    }

    int size() {
        synchronized (lock) {
            return connections.size();
        }
    }

    boolean awaitingFirst() {
        return all().stream().anyMatch(Connection::awaitingFirst);
    }

    /**
     * 바뀐 항목과 지금 안 읽은 수를 보낸다. 바뀐 항목이 있으면 모든 연결에(탭이 맞는 항목만 + unread), 없으면 첫 unread 를 기다리는 연결에만 보낸다.
     */
    void publish(List<AdminAlertResponse> changed, Map<Audience, AdminAlertResponse.UnreadCounts> unread) {
        for (Connection connection : all()) {
            if (changed.isEmpty() && !connection.awaitingFirst()) {
                continue;
            }
            List<SseEmitter.SseEventBuilder> events = new ArrayList<>();
            for (AdminAlertResponse item : changed) {
                if (connection.audience == null || connection.audience == item.audience()) {
                    events.add(SseEmitter.event().name(NOTIFICATION_EVENT).data(item, MediaType.APPLICATION_JSON));
                }
            }
            events.add(SseEmitter.event().name(UNREAD_EVENT).data(unread, MediaType.APPLICATION_JSON));
            connection.offer(events);
        }
    }

    /** 대량 변경 — 모든 연결에 resync 하나와 unread 를 보낸다. 화면은 목록을 새로 읽는다. */
    void resync(Map<Audience, AdminAlertResponse.UnreadCounts> unread) {
        for (Connection connection : all()) {
            connection.offer(List.of(SseEmitter.event().name(RESYNC_EVENT).data(Map.of(), MediaType.APPLICATION_JSON),
                    SseEmitter.event().name(UNREAD_EVENT).data(unread, MediaType.APPLICATION_JSON)));
        }
    }

    private static BusinessException unavailable(String why) {
        return new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE, "관리자 알림 실시간 연결을 받지 않는다 — " + why);
    }

    private void forget(Connection connection) {
        connection.done = true;
        remove(connection);
    }

    private void broken(Connection connection, String reason) {
        connection.countClosed(reason);
        remove(connection);
        connection.complete();
    }

    private void remove(Connection connection) {
        synchronized (lock) {
            connections.remove(connection);
        }
    }

    private List<Connection> all() {
        synchronized (lock) {
            return List.copyOf(connections);
        }
    }

    void beat() {
        for (Connection connection : all()) {
            connection.offerHeartbeat();
        }
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        synchronized (lock) {
            closed = false;
        }
        heartbeats = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("admin-alert-stream-heartbeat").daemon().factory());
        long period = properties.heartbeat().toMillis();
        heartbeats.scheduleWithFixedDelay(() -> {
            // 예외가 새면 ScheduledExecutorService 는 다음 주기를 돌리지 않는다
            try {
                beat();
            } catch (RuntimeException e) {
                log.warn("관리자 알림 실시간 연결 주기 신호를 보내다 실패했다 — 다음 주기에 다시 보낸다", e);
            }
        }, period, period, TimeUnit.MILLISECONDS);
        running = true;
    }

    @Override
    public synchronized void stop() {
        running = false;
        List<Connection> open;
        synchronized (lock) {
            closed = true;
            open = List.copyOf(connections);
            connections.clear();
        }
        if (heartbeats != null) {
            heartbeats.shutdownNow();
        }
        open.forEach(Connection::complete);
        if (!open.isEmpty()) {
            log.info("종료 — 관리자 알림 실시간 연결 {}개를 닫았다. 화면은 다른 태스크로 다시 연결한다", open.size());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 웹 서버의 정상 종료(WebServerGracefulShutdownLifecycle, MAX_VALUE − 1024)보다 먼저 멈춘다. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    /** 연결 하나. 이 객체의 잠금은 상태만 지키고 쓰는 동안에는 쥐지 않는다. */
    final class Connection {

        final String principal;
        final Audience audience;
        final SseEmitter emitter;
        volatile boolean done;
        private boolean sentFirst;
        private boolean writing;
        /** 쌓인 것을 다 쓰면 닫는다(replaced 를 쓴 뒤) */
        private boolean closeAfterDrain;
        private final AtomicBoolean counted = new AtomicBoolean();
        private final Deque<SseEmitter.SseEventBuilder> pending = new ArrayDeque<>();

        Connection(String principal, Audience audience, SseEmitter emitter) {
            this.principal = principal;
            this.audience = audience;
            this.emitter = emitter;
        }

        synchronized boolean awaitingFirst() {
            return !sentFirst && !done;
        }

        /** 차례대로 쌓고, 쓰는 중이 아니면 쓰기를 시작한다. 쌓인 것이 상한을 넘으면 닫는다 — 화면이 다시 연결해 새로 읽는다. */
        void offer(List<SseEmitter.SseEventBuilder> events) {
            boolean overflow;
            synchronized (this) {
                if (done) {
                    return;
                }
                sentFirst = true;
                overflow = pending.size() + events.size() > properties.maxPending();
                if (!overflow) {
                    pending.addAll(events);
                    if (!writing) {
                        writing = true;
                        writers.newThread(this::drain).start();
                    }
                }
            }
            if (overflow) {
                log.warn("관리자 알림 실시간 연결이 쓰지 못한 사건이 {}개를 넘어 닫는다", properties.maxPending());
                broken(this, "overflow");
            }
        }

        /** 닫는 까닭을 한 번만 센다 — 넘침 뒤 쓰기 실패처럼 겹쳐도 두 번 세지 않는다. */
        void countClosed(String reason) {
            if (counted.compareAndSet(false, true)) {
                meterRegistry.counter(CLOSED_METRIC, "reason", reason).increment();
            }
        }

        /** 쌓인 사건을 버리고 replaced 하나만 쓴 뒤 닫는다. 이미 목록에서 뺐다. */
        void replace() {
            countClosed("replaced");
            boolean start;
            synchronized (this) {
                if (done) {
                    return;
                }
                done = true;
                pending.clear();
                pending.add(SseEmitter.event().name(REPLACED_EVENT)
                        .data(Map.of("reason", "MAX_PER_PRINCIPAL"), MediaType.APPLICATION_JSON));
                closeAfterDrain = true;
                start = !writing;
                writing = true;
            }
            if (start) {
                writers.newThread(this::drain).start();
            }
        }

        /** 쓰는 중이면 건너뛴다 — 이미 오가는 것이 있다. */
        void offerHeartbeat() {
            synchronized (this) {
                if (done || writing) {
                    return;
                }
                pending.add(SseEmitter.event().comment(""));
                writing = true;
            }
            writers.newThread(this::drain).start();
        }

        private void drain() {
            while (true) {
                SseEmitter.SseEventBuilder event;
                boolean closeNow = false;
                synchronized (this) {
                    event = pending.poll();
                    if (event == null) {
                        writing = false;
                        closeNow = closeAfterDrain;
                    }
                }
                if (event == null) {
                    if (closeNow) {
                        completeNow();
                    }
                    return;
                }
                try {
                    emitter.send(event);
                } catch (IOException e) {
                    broken(this, "disconnected");
                    return;
                } catch (RuntimeException e) {
                    // 그사이 닫힌 연결은 원인 없는 IllegalStateException 이라 평범한 닫힘으로 둔다. 그 밖은 버그일 수 있어 남기고 error 로 센다
                    if (!(e instanceof IllegalStateException) || e.getCause() != null) {
                        log.warn("관리자 알림 실시간 연결에 쓰다 예기치 않게 실패해 닫는다", e);
                        broken(this, "error");
                    } else {
                        remove(this);
                        complete();
                    }
                    return;
                }
            }
        }

        void complete() {
            done = true;
            writers.newThread(this::completeNow).start();
        }

        /** 쓰다 실패한 연결은 컨테이너가 이미 오류로 끝냈다 — 그때 Tomcat 은 앱 스레드의 완료를 거절한다. */
        void completeNow() {
            done = true;
            try {
                emitter.complete();
            } catch (IllegalStateException e) {
                log.debug("이미 끝난 관리자 알림 실시간 연결");
            }
        }
    }
}
