package com.grandis.nova.member.adminalert;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 연결을 가진 인스턴스가 tail-interval(2초)마다 바뀐 알림을 찾아 보낸다. 연결이 없으면 읽지 않는다. 회원 알림의 NotificationTail 과 같은 방식이되,
 * 행이 제자리에서 바뀌므로(숫자 갱신 · 해소 · 읽음) id 가 아니라 changed_at 으로 찾는다.
 *
 * 앞 주기를 시작한 시각보다 overlap(10초) 앞부터 (changed_at, id) 순으로 쪽(500행)씩 읽는다 — changed_at 은 커밋 전에 앱 시계로 적혀, 앞 주기 뒤에
 * 커밋된 행의 시각이 그보다 조금 이를 수 있다. 그 창 안에서 보낸 (id, version)은 기억해 두 번 보내지 않는다 — version 은 바뀔 때마다 오르므로 같은
 * 마이크로초에 두 번 바뀌어도 두 번째 변경을 놓치지 않는다. 한 주기에 바뀐 행이 resync-threshold 를 넘으면(모두 읽음 같은 대량 변경) 행마다 보내지 않고
 * resync 하나로 알린다 — 행 사건이 연결의 쌓기 상한을 넘겨 열린 탭이 모두 닫히지 않게. 한 주기가 실패하면 자리를 두고 다음 주기에 다시 한다.
 * 주기는 앞 주기가 끝난 뒤부터 재고, 스레드는 따로 둔다.
 */
@Component
class AdminAlertTail implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AdminAlertTail.class);

    static final int PAGE = 500;
    static final String TIMER = "member.admin-alert.stream.tail";
    /** 연달아 실패하면 첫 번과 이 횟수마다 한 번만 남긴다(약 1분 간격) */
    static final int WARN_EVERY = 30;

    private final AdminAlertStreams streams;
    private final AdminAlertQueries queries;
    private final AdminAlertService service;
    private final Duration interval;
    private final Duration overlap;
    private final int resyncThreshold;
    private final Clock clock;
    private final Timer timer;

    // 아래 셋은 tick 의 잠금으로 지킨다
    /** 다음 주기가 읽기 시작할 시각. 연결이 없으면 null */
    private Instant from;
    /** 겹침 창 안에서 이미 보낸 행의 version 과 바뀐 시각(창 밖으로 나가면 잊는다) */
    private final Map<UUID, Seen> seen = new HashMap<>();
    private int failures;

    private final Object lifecycle = new Object();
    private ScheduledExecutorService executor;
    private volatile boolean running;

    AdminAlertTail(AdminAlertStreams streams, AdminAlertQueries queries, AdminAlertService service,
                   AdminAlertStreamProperties properties, Clock clock, MeterRegistry meterRegistry) {
        this.streams = streams;
        this.queries = queries;
        this.service = service;
        this.interval = properties.tailInterval();
        this.overlap = properties.overlap();
        this.resyncThreshold = properties.resyncThreshold();
        this.clock = clock;
        this.timer = Timer.builder(TIMER).register(meterRegistry);
    }

    /** 한 주기. 예외를 내보내지 않는다. 시험이 직접 불러도 주기 스레드와 겹치지 않게 잠근다. */
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
            List<AdminAlertQueries.View> fresh = readFrom(from);
            if (fresh.size() > resyncThreshold) {
                streams.resync(service.unreadCounts());
            } else if (!fresh.isEmpty() || streams.awaitingFirst()) {
                streams.publish(fresh.stream().map(service::response).toList(), service.unreadCounts());
            }
            // 보낸 뒤에야 다음 자리로 옮긴다 — 중간에 실패하면 다음 주기가 같은 자리부터 다시 읽어 보낸다
            fresh.forEach(view -> seen.put(view.id(), new Seen(view.version(), view.changedAt())));
            from = now.minus(overlap);
            seen.values().removeIf(sent -> sent.changedAt().isBefore(from));
            if (failures > 0) {
                log.info("관리자 알림 꼬리 읽기가 돌아왔다 — 연달아 {}번 실패했었다", failures);
                failures = 0;
            }
        } catch (RuntimeException e) {
            failures++;
            if (failures == 1 || failures % WARN_EVERY == 0) {
                log.warn("바뀐 관리자 알림을 읽지 못했다 — 자리를 두고 다음 주기에 다시 읽는다 failures={}", failures, e);
            }
        } finally {
            sample.stop(timer);
        }
    }

    private List<AdminAlertQueries.View> readFrom(Instant start) {
        List<AdminAlertQueries.View> fresh = new ArrayList<>();
        AdminAlertQueries.View after = null;
        while (true) {
            List<AdminAlertQueries.View> page = queries.changed(start, after, PAGE);
            for (AdminAlertQueries.View view : page) {
                Seen sent = seen.get(view.id());
                if (sent == null || sent.version() != view.version()) {
                    fresh.add(view);
                }
            }
            if (page.size() < PAGE) {
                return fresh;
            }
            after = page.getLast();
        }
    }

    private record Seen(long version, Instant changedAt) {
    }

    @Override
    public void start() {
        synchronized (lifecycle) {
            if (running) {
                return;
            }
            executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("admin-alert-stream-tail").daemon().factory());
            long period = interval.toMillis();
            executor.scheduleWithFixedDelay(this::tick, period, period, TimeUnit.MILLISECONDS);
            running = true;
        }
    }

    /** 도는 주기는 끝까지 기다리되 오래 걸리면 끊는다 — 데이터소스가 닫히기 전에 멈춘다. */
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
