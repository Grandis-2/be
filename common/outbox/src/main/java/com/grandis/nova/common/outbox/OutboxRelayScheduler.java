package com.grandis.nova.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 릴레이 전용 실행기. 서비스의 기본 스케줄러(스레드 1개)를 같이 쓰면 릴레이 한 번(최악 relay-batch × 전송 제한 시간)이
 * 그 스레드를 쥐어 다른 @Scheduled 작업이 밀린다. 미발행 현황 지표는 둘째 스레드에서 따로 세어, 릴레이가 오래 걸리는 장애 때도 멈추지 않는다.
 *
 * 실행기를 빈으로 두지 않고 @EnableScheduling 도 쓰지 않는다 — TaskScheduler · ScheduledExecutorService 빈이 있으면
 * Boot 가 기본 taskScheduler 를 만들지 않아 서비스의 @Scheduled 가 모두 이 실행기로 몰리고, 서비스의 스케줄링 켜기를
 * 공통 모듈이 대신 정하게 된다. 주기 실행은 앞 실행이 끝난 뒤부터 잰다(겹치지 않는다).
 */
class OutboxRelayScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);
    /** 인터럽트한 뒤 진행 중인 전송이 끊기고 실패를 적기까지 기다리는 시간. */
    private static final Duration INTERRUPTED_GRACE = Duration.ofSeconds(5);

    private final OutboxRelay relay;
    private final OutboxMetrics metrics;
    private final Duration interval;
    private final Duration stopTimeout;
    private ScheduledExecutorService executor;

    /** @param stopTimeout 종료 때 보내던 한 건을 기다리는 시간. 전송 한 번의 최대 시간에 여유를 더한다 */
    OutboxRelayScheduler(OutboxRelay relay, OutboxMetrics metrics, Duration interval, Duration stopTimeout) {
        this.relay = relay;
        this.metrics = metrics;
        this.interval = interval;
        this.stopTimeout = stopTimeout;
    }

    @Override
    public synchronized void start() {
        relay.resume();
        executor = Executors.newScheduledThreadPool(2, Thread.ofPlatform().name("outbox-relay-", 0).daemon().factory());
        long millis = interval.toMillis();
        executor.scheduleWithFixedDelay(() -> runQuietly("릴레이", relay::relay), millis, millis, TimeUnit.MILLISECONDS);
        if (metrics.tracksBacklog()) {
            executor.scheduleWithFixedDelay(() -> runQuietly("미발행 현황 집계", metrics::refreshBacklog),
                    0, millis, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 새 실행을 멈추고, 진행 중인 릴레이에는 보내던 한 건만 마치게 한다(남은 행은 리스를 풀어 다른 인스턴스가 곧바로 가져간다).
     * 그 한 건이 종료 시간을 넘기면 인터럽트한다 — 그 행은 실패로 세어지고 다음 릴레이가 다시 보낸다.
     * 종료 단계에서 기다리는 시간이 전송 한 번 남짓이라, 뒤 단계(웹 서버 종료 · DB 풀 닫기)를 오래 붙잡지 않는다.
     */
    @Override
    public synchronized void stop() {
        if (executor == null) {
            return;
        }
        relay.requestStop();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(stopTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("아웃박스 릴레이가 종료 시간 안에 끝나지 않아 인터럽트한다");
                executor.shutdownNow();
                executor.awaitTermination(INTERRUPTED_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        executor = null;
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }

    /**
     * 주기 작업에서 새는 예외는 남기고 삼킨다 — 하나라도 밖으로 나가면 실행기가 그 작업의 다음 실행을 조용히 멈춘다.
     * 메모리 부족만 다시 던진다(이어 돌려도 또 실패한다).
     */
    private static void runQuietly(String task, Runnable body) {
        try {
            body.run();
        } catch (OutOfMemoryError e) {
            log.error("아웃박스 {} 중 메모리 부족 — 이 주기 작업은 멈춘다", task, e);
            throw e;
        } catch (RuntimeException | Error e) {
            log.error("아웃박스 {} 실패 — 다음 주기에 다시 한다", task, e);
        }
    }
}
