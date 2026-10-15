package com.grandis.nova.member.notification;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 이 인스턴스에 열린 안 읽은 수 실시간 연결(회원 → 연결들).
 *
 * 연결은 DB 를 읽지 않고 등록만 한다 — 배포 뒤 수천 연결이 한꺼번에 다시 붙어도 풀(10)과 요청 스레드를 붙잡지 않게. 첫 수는
 * {@link NotificationTail} 이 다음 주기에 다른 회원과 묶어 세어 {@link #push} 로 보낸다. 세기 직전에 전역 순번을 받고 연결은 이미 넘긴 것보다
 * 큰 순번의 수만 쓴다 — 먼저 센 수가 늦게 와 새 수를 덮지 않게. 순번은 세기 시작 순서일 뿐이라 드물게 뒤 순번이 조금 앞선 수를 실을 수 있고,
 * 다음 새 알림이나 화면의 안전망 조회가 바로잡는다.
 *
 * 쓰기는 연결마다 가상 스레드 하나로 한다 — 버퍼가 찬 클라이언트 하나가 다른 연결의 주기 신호 · 수를 막지 않게. 쓰는 중에 온 수는 가장 새 것 하나만
 * 들고 있다가 이어 쓰고, 주기 신호는 건너뛴다(쓰는 중이면 이미 오가는 것이 있다). 닫기도 같은 까닭으로 가상 스레드에서 한다. 목록 잠금을 쥔 채로는
 * 쓰지도 닫지도 않는다.
 *
 * 주기 신호는 전용 스레드 하나가 돌린다. member 의 {@code @Scheduled} 는 스레드 하나를 리프레시 정리와 나눠 써 서로 늦출 수 있다.
 * 종료는 웹 서버의 정상 종료보다 먼저 한다(단계가 더 높다) — 모든 연결을 닫아 열린 스트림을 기다리느라 종료가 늘지 않게 하고,
 * 화면은 새 태스크로 다시 붙는다. 그 뒤 들어온 연결은 503 이다.
 */
@Component
class NotificationStreams implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NotificationStreams.class);

    static final String METRIC = "member.notification.streams";
    static final String UNREAD_EVENT = "unread";

    private final NotificationStreamProperties properties;
    private final Clock clock;
    private final AtomicLong sequence = new AtomicLong();
    private final ThreadFactory writers = Thread.ofVirtual().name("notification-stream-write").factory();

    private final Object lock = new Object();
    /** lock 으로 지킨다. 회원마다 연 순서대로 — 넘치면 맨 앞(가장 오래된 것)을 닫는다 */
    private final Map<UUID, List<Connection>> byCustomer = new HashMap<>();
    private int size;
    private boolean closed;

    private ScheduledExecutorService heartbeats;
    private volatile boolean running;

    NotificationStreams(NotificationStreamProperties properties, Clock clock, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.clock = clock;
        Gauge.builder(METRIC, this, NotificationStreams::size).register(meterRegistry);
    }

    /**
     * 연결을 연다. 첫 수는 다음 주기(tail-interval)에 온다.
     *
     * @param tokenExpiresAt 연결을 연 액세스 토큰의 만료. 연결이 이보다 오래 살지 않는다 — null 이면 최대 수명만 본다
     * @throws BusinessException 503 — 이 인스턴스의 연결이 가득 찼거나 종료 중이다
     */
    SseEmitter open(UUID customerId, Instant tokenExpiresAt) {
        return register(customerId, new SseEmitter(lifetime(tokenExpiresAt).toMillis())).emitter;
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

    /** 안 읽은 수를 세기 직전에 받는다. */
    long nextSequence() {
        return sequence.incrementAndGet();
    }

    /**
     * 회원의 이 인스턴스 연결 모두에 수를 넘긴다. 쓰기는 연결마다 따로 돌아 기다리지 않는다.
     *
     * @param order 수를 세기 직전에 받은 순번({@link #nextSequence()})
     */
    void push(UUID customerId, long order, long count) {
        for (Connection connection : connectionsOf(customerId)) {
            connection.offerUnread(order, count);
        }
    }

    /** @return 아직 첫 수를 받지 못한 연결이 있는 회원 */
    Set<UUID> awaitingFirstCount() {
        Set<UUID> customers = new HashSet<>();
        synchronized (lock) {
            byCustomer.forEach((customerId, mine) -> {
                if (mine.stream().anyMatch(Connection::awaitingFirstCount)) {
                    customers.add(customerId);
                }
            });
        }
        return customers;
    }

    int size() {
        synchronized (lock) {
            return size;
        }
    }

    Connection register(UUID customerId, SseEmitter emitter) {
        Connection connection = new Connection(this, customerId, emitter);
        emitter.onCompletion(() -> forget(connection));
        emitter.onError(e -> forget(connection));
        // 시간이 다 되면 이 콜백 안에서 닫는다. 그냥 두면 MVC 가 이미 나간 스트림 위에 503 을 쓰려 한다
        emitter.onTimeout(connection::completeNow);
        Connection evicted = null;
        synchronized (lock) {
            if (closed) {
                throw unavailable("종료 중");
            }
            List<Connection> mine = byCustomer.computeIfAbsent(customerId, id -> new ArrayList<>());
            if (mine.size() >= properties.maxPerCustomer()) {
                // 바꿔 끼우는 것이라 인스턴스 상한에 걸려도 받는다
                evicted = mine.removeFirst();
                size--;
            } else if (size >= properties.maxConnections()) {
                if (mine.isEmpty()) {
                    byCustomer.remove(customerId);
                }
                throw unavailable("연결 " + size + "개로 가득 찼다");
            }
            mine.add(connection);
            size++;
        }
        if (evicted != null) {
            evicted.complete();
        }
        return connection;
    }

    private static BusinessException unavailable(String why) {
        return new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE, "알림 실시간 연결을 받지 않는다 — " + why);
    }

    private List<Connection> connectionsOf(UUID customerId) {
        synchronized (lock) {
            return List.copyOf(byCustomer.getOrDefault(customerId, List.of()));
        }
    }

    /** 쓰다 실패한 연결. 클라이언트가 떠났다 — 빼고 닫는다. */
    void broken(Connection connection) {
        remove(connection);
        connection.complete();
    }

    /** 컨테이너가 끝낸 연결(완료 · 시간 초과 · 오류)을 뺀다. */
    private void forget(Connection connection) {
        connection.done = true;
        remove(connection);
    }

    private void remove(Connection connection) {
        synchronized (lock) {
            List<Connection> mine = byCustomer.get(connection.customerId);
            if (mine != null && mine.remove(connection)) {
                size--;
                if (mine.isEmpty()) {
                    byCustomer.remove(connection.customerId);
                }
            }
        }
    }

    private List<Connection> all() {
        synchronized (lock) {
            List<Connection> all = new ArrayList<>(size);
            byCustomer.values().forEach(all::addAll);
            return all;
        }
    }

    /** 연결이 없으면 아무것도 하지 않는다. 떠난 클라이언트는 쓸 때에야 드러나 그때 닫힌다. */
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
        heartbeats = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("notification-stream-heartbeat").daemon().factory());
        long period = properties.heartbeat().toMillis();
        heartbeats.scheduleWithFixedDelay(() -> {
            // 예외가 새면 ScheduledExecutorService 는 다음 주기를 돌리지 않는다
            try {
                beat();
            } catch (RuntimeException e) {
                log.warn("실시간 연결 주기 신호를 보내다 실패했다 — 다음 주기에 다시 보낸다", e);
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
            open = new ArrayList<>(size);
            byCustomer.values().forEach(open::addAll);
            byCustomer.clear();
            size = 0;
        }
        if (heartbeats != null) {
            heartbeats.shutdownNow();
        }
        open.forEach(Connection::complete);
        if (!open.isEmpty()) {
            log.info("종료 — 실시간 연결 {}개를 닫았다. 화면은 다른 태스크로 다시 연결한다", open.size());
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

    /** 연결 하나. 이 객체의 잠금은 상태만 지키고 쓰는 동안에는 쥐지 않는다 — 쓰기는 {@link #drain} 이 가상 스레드에서 한다. */
    static final class Connection {

        final UUID customerId;
        final SseEmitter emitter;
        private final NotificationStreams owner;
        /** 잠금 없이 읽고 쓴다 — 컨테이너 콜백이 기다리지 않게 */
        volatile boolean done;
        /** 마지막으로 쓰기에 넘긴 순번. 0 이면 아직 첫 수를 받지 못했다 */
        private long lastSequence;
        private boolean writing;
        /** 쓰는 중에 온 가장 새 수. 쓰기가 끝나면 이어 쓴다 */
        private SseEmitter.SseEventBuilder held;

        Connection(NotificationStreams owner, UUID customerId, SseEmitter emitter) {
            this.owner = owner;
            this.customerId = customerId;
            this.emitter = emitter;
        }

        synchronized boolean awaitingFirstCount() {
            return lastSequence == 0 && !done;
        }

        /** 더 새 수만 받는다. 쓰는 중이면 들고 있다가(앞서 들고 있던 것은 버린다) 지금 쓰기가 끝나면 쓴다. */
        void offerUnread(long order, long count) {
            SseEmitter.SseEventBuilder event = SseEmitter.event().name(UNREAD_EVENT)
                    .data(new NotificationController.UnreadCount(count), MediaType.APPLICATION_JSON);
            synchronized (this) {
                if (done || order <= lastSequence) {
                    return;
                }
                lastSequence = order;
                if (writing) {
                    held = event;
                    return;
                }
                writing = true;
            }
            owner.writers.newThread(() -> drain(event)).start();
        }

        void offerHeartbeat() {
            synchronized (this) {
                if (done || writing) {
                    return;
                }
                writing = true;
            }
            // 빌더는 쓸 때 고쳐지므로(build) 연결마다 새로 만든다
            owner.writers.newThread(() -> drain(SseEmitter.event().comment(""))).start();
        }

        private void drain(SseEmitter.SseEventBuilder first) {
            SseEmitter.SseEventBuilder event = first;
            while (event != null) {
                try {
                    emitter.send(event);
                } catch (IOException | IllegalStateException e) {
                    // 떠난 클라이언트(IOException)이거나 이미 닫힌 연결(IllegalStateException)이다
                    owner.broken(this);
                    return;
                }
                synchronized (this) {
                    event = held;
                    held = null;
                    if (event == null) {
                        writing = false;
                    }
                }
            }
        }

        /** 쓰는 중이면 에미터 잠금을 기다릴 수 있어 가상 스레드에서 닫는다. */
        void complete() {
            done = true;
            owner.writers.newThread(this::completeNow).start();
        }

        /** 쓰다 실패한 연결은 컨테이너가 이미 오류로 끝냈다 — 그때 Tomcat 은 앱 스레드의 완료를 거절한다(실측: IllegalStateException). */
        void completeNow() {
            done = true;
            try {
                emitter.complete();
            } catch (IllegalStateException e) {
                log.debug("이미 끝난 실시간 연결 customerId={}", customerId);
            }
        }
    }
}
