package com.grandis.nova.member.notification;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 연결을 가진 인스턴스가 tail-interval(2초)마다 DB 에서 안 읽은 수를 세어 보낸다. 연결이 없으면 읽지 않는다. 세는 회원은 둘이다 — 첫 수를 기다리는
 * 연결의 회원, 그리고 새 알림이 생긴 회원 중 이 인스턴스에 연결된 회원. 합쳐서 500명씩 한 번에 센다(ix_notification_customer_unread).
 *
 * 알림을 저장한 인스턴스가 그 회원의 연결을 갖고 있다는 보장이 없어(태스크 1~3대 + 블루/그린 1대) 인스턴스마다 스스로 새 알림을 찾는다. 서버 사이
 * 메시지(Valkey pub/sub)를 두지 않은 까닭은 연결이 없을 때 비용이 0 이고 놓친 메시지를 되살릴 일이 없어서다 — 대신 2초 지연을 받는다.
 *
 * 새 행은 기본키 범위로 찾는다(v7 id 라 id 순서가 만든 시각 순서다). 앞 주기를 시작한 시각보다 overlap(10초) 앞의 가장 작은 id 부터 쪽(500행)씩
 * 읽는다 — id 는 앱 메모리에서 커밋 전에 만들어져 앞 주기 뒤에 커밋된 행의 id 가 그보다 조금 오래될 수 있다. 그 창 안에서 보낸 id 는 기억해 두 번
 * 보내지 않는다. 읽는 폭은 늘 "주기 + 10초" 라 몰린 알림도 10초 뒤에는 다시 읽지 않는다.
 *
 * 한 주기가 실패하면(DB 장애) 읽는 자리와 본 id 를 그대로 두고 다음 주기에 다시 한다 — 첫 주기의 자리는 읽기 전에 정해 실패해도 틈이 없다.
 * 주기는 앞 주기가 끝난 뒤부터 잰다(겹치지 않는다). 스레드는 따로 둔다 — DB 연결 대기(최대 30초)가 주기 신호 · 리프레시 정리를 늦추지 않게.
 */
@Component
class NotificationTail implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NotificationTail.class);

    static final int PAGE = 500;
    static final int COUNT_CHUNK = 500;
    /** 한 주기(연결이 있어 읽은 것만)에 걸린 시간 */
    static final String TIMER = "member.notification.stream.tail";
    /** 연달아 실패하면 첫 번과 이 횟수마다 한 번만 남긴다 — DB 장애 동안 2초마다 경고가 쌓이지 않게(약 1분 간격) */
    static final int WARN_EVERY = 30;

    private final NotificationStreams streams;
    private final NotificationTailQueries queries;
    private final Duration interval;
    private final Duration overlap;
    private final Clock clock;
    private final Timer timer;

    // 아래 셋은 tick 의 잠금으로 지킨다
    /** 다음 주기가 읽기 시작할 시각(앞 주기의 시작 − overlap). 연결이 없으면 null */
    private Instant from;
    /** 겹침 창 안에서 이미 보낸 id */
    private final Set<UUID> seen = new HashSet<>();
    private int failures;

    /** 시작 · 멈춤은 tick 과 다른 잠금을 쓴다 — 도는 주기(DB 대기)를 기다리지 않고 멈춤을 시작하게 */
    private final Object lifecycle = new Object();
    private ScheduledExecutorService executor;
    private volatile boolean running;

    NotificationTail(NotificationStreams streams, NotificationTailQueries queries, NotificationStreamProperties properties,
                     Clock clock, MeterRegistry meterRegistry) {
        this.streams = streams;
        this.queries = queries;
        this.interval = properties.tailInterval();
        this.overlap = properties.overlap();
        this.clock = clock;
        this.timer = Timer.builder(TIMER).register(meterRegistry);
    }

    /** 한 주기. 예외를 내보내지 않는다 — 실패는 남기고 다음 주기에 다시 한다. 시험이 직접 불러도 주기 스레드와 겹치지 않게 잠근다. */
    synchronized void tick() {
        if (streams.size() == 0) {
            from = null;
            seen.clear();
            return;
        }
        Instant now = clock.instant();
        if (from == null) {
            from = now.minus(overlap);
        }
        Timer.Sample sample = Timer.start();
        try {
            Set<UUID> customers = new HashSet<>(streams.awaitingFirstCount());
            List<NotificationTailQueries.Row> fresh = readFrom(from);
            customers.addAll(streams.connected(
                    fresh.stream().map(NotificationTailQueries.Row::customerId).collect(Collectors.toSet())));
            push(customers);
            // 보낸 뒤에야 다음 자리로 옮긴다 — 중간에 실패하면 다음 주기가 같은 자리부터 다시 읽어 보낸다
            fresh.forEach(row -> seen.add(row.id()));
            from = now.minus(overlap);
            long floor = from.toEpochMilli();
            seen.removeIf(id -> timeOf(id).toEpochMilli() < floor);
            if (failures > 0) {
                log.info("안 읽은 수 세기가 돌아왔다 — 연달아 {}번 실패했었다", failures);
                failures = 0;
            }
        } catch (RuntimeException e) {
            failures++;
            if (failures == 1 || failures % WARN_EVERY == 0) {
                log.warn("안 읽은 수를 세지 못했다 — 자리를 두고 다음 주기에 다시 읽는다 failures={}", failures, e);
            }
        } finally {
            sample.stop(timer);
        }
    }

    /** start 의 가장 작은 id 부터 끝까지 쪽씩 읽어 아직 보내지 않은 행만 돌려준다. */
    private List<NotificationTailQueries.Row> readFrom(Instant start) {
        List<NotificationTailQueries.Row> fresh = new ArrayList<>();
        UUID bound = smallestIdAt(start);
        boolean inclusive = true;
        while (true) {
            List<NotificationTailQueries.Row> page = queries.page(bound, inclusive, PAGE);
            for (NotificationTailQueries.Row row : page) {
                if (!seen.contains(row.id())) {
                    fresh.add(row);
                }
            }
            if (page.size() < PAGE) {
                return fresh;
            }
            bound = page.getLast().id();
            inclusive = false;
        }
    }

    private void push(Collection<UUID> customerIds) {
        List<UUID> customers = new ArrayList<>(customerIds);
        for (int start = 0; start < customers.size(); start += COUNT_CHUNK) {
            List<UUID> chunk = customers.subList(start, Math.min(start + COUNT_CHUNK, customers.size()));
            long order = streams.nextSequence();
            Map<UUID, Long> counts = queries.unreadCounts(chunk);
            for (UUID customerId : chunk) {
                streams.push(customerId, order, counts.getOrDefault(customerId, 0L));
            }
        }
    }

    /** 그 밀리초의 v7 id 보다 작거나 같은 가장 작은 값(시각 뒤는 모두 0). BINARY(16) 비교에서도 그대로 가장 작다. */
    static UUID smallestIdAt(Instant at) {
        return new UUID(at.toEpochMilli() << 16, 0L);
    }

    /** v7 id 의 앞 48비트 = 만든 밀리초. */
    static Instant timeOf(UUID id) {
        return Instant.ofEpochMilli(id.getMostSignificantBits() >>> 16);
    }

    @Override
    public void start() {
        synchronized (lifecycle) {
            if (running) {
                return;
            }
            executor = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("notification-stream-tail").daemon().factory());
            long period = interval.toMillis();
            executor.scheduleWithFixedDelay(this::tick, period, period, TimeUnit.MILLISECONDS);
            running = true;
        }
    }

    /** 도는 주기는 끝까지 기다리되 오래 걸리면(DB 연결 대기) 끊는다 — 데이터소스가 닫히기 전에 멈춘다. */
    @Override
    public void stop() {
        ScheduledExecutorService current;
        synchronized (lifecycle) {
            running = false;
            current = executor;
        }
        if (current == null) {
            return;
        }
        current.shutdown();
        try {
            if (!current.awaitTermination(5, TimeUnit.SECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException e) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
