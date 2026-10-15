package com.grandis.nova.member.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 연결을 가진 인스턴스가 tail-interval(2초)마다 DB 에서 안 읽은 수를 세어 보낸다. 연결이 없으면 읽지 않는다. 지금 세는 회원은 첫 수를 기다리는 연결의
 * 회원이다 — 연결이 DB 를 읽지 않으므로 한 주기의 새 연결을 500명씩 한 번에 센다(ix_notification_customer_unread).
 *
 * 한 주기가 실패하면(DB 장애) 다음 주기에 다시 센다 — 받지 못한 연결은 계속 첫 수를 기다리는 것으로 남는다. 주기는 앞 주기가 끝난 뒤부터 잰다
 * (겹치지 않는다). 스레드는 따로 둔다 — DB 연결 대기(최대 30초)가 주기 신호 · 리프레시 정리를 늦추지 않게.
 */
@Component
class NotificationTail implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NotificationTail.class);

    static final int COUNT_CHUNK = 500;
    /** 연달아 실패하면 첫 번과 이 횟수마다 한 번만 남긴다 — DB 장애 동안 2초마다 경고가 쌓이지 않게(약 1분 간격) */
    static final int WARN_EVERY = 30;

    private final NotificationStreams streams;
    private final NotificationTailQueries queries;
    private final Duration interval;

    private int failures;
    private ScheduledExecutorService executor;
    private volatile boolean running;

    NotificationTail(NotificationStreams streams, NotificationTailQueries queries, NotificationStreamProperties properties) {
        this.streams = streams;
        this.queries = queries;
        this.interval = properties.tailInterval();
    }

    /** 한 주기. 예외를 내보내지 않는다 — 실패는 남기고 다음 주기에 다시 한다. 시험이 직접 불러도 주기 스레드와 겹치지 않게 잠근다. */
    synchronized void tick() {
        if (streams.size() == 0) {
            return;
        }
        try {
            push(streams.awaitingFirstCount());
            if (failures > 0) {
                log.info("안 읽은 수 세기가 돌아왔다 — 연달아 {}번 실패했었다", failures);
                failures = 0;
            }
        } catch (RuntimeException e) {
            failures++;
            if (failures == 1 || failures % WARN_EVERY == 0) {
                log.warn("안 읽은 수를 세지 못했다 — 다음 주기에 다시 센다 failures={}", failures, e);
            }
        }
    }

    private void push(Collection<UUID> customerIds) {
        List<UUID> customers = new ArrayList<>(customerIds);
        for (int from = 0; from < customers.size(); from += COUNT_CHUNK) {
            List<UUID> chunk = customers.subList(from, Math.min(from + COUNT_CHUNK, customers.size()));
            long order = streams.nextSequence();
            Map<UUID, Long> counts = queries.unreadCounts(chunk);
            for (UUID customerId : chunk) {
                streams.push(customerId, order, counts.getOrDefault(customerId, 0L));
            }
        }
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("notification-stream-tail").daemon().factory());
        long period = interval.toMillis();
        executor.scheduleWithFixedDelay(this::tick, period, period, TimeUnit.MILLISECONDS);
        running = true;
    }

    /** 도는 주기는 끝까지 기다리되 오래 걸리면(DB 연결 대기) 끊는다 — 데이터소스가 닫히기 전에 멈춘다. */
    @Override
    public void stop() {
        ScheduledExecutorService current;
        synchronized (this) {
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
