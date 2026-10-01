package com.grandis.nova.waitingroom.control;

import com.grandis.nova.waitingroom.redis.ControlStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongFunction;

/**
 * 제어 루프 셋 — 리더 리스 연장, 틱(하트비트 + 리더면 배분 회차), 판정 재료 받기. 루프는 한 번 실패해도 멈추지 않고
 * 다음 주기에 다시 돈다. 실패 · 회복은 상태가 바뀔 때만 남긴다. 멈출 때는 리스를 놓고 분모에서 빠진다.
 */
@Component
@ConditionalOnProperty(name = "waitingroom.control.enabled", matchIfMissing = true)
class ControlPlane implements SmartLifecycle {

    /** 웹 서버(우아한 종료 포함)보다 먼저 시작하고 나중에 멈춘다 — 남은 요청을 비우는 동안에도 판정 재료가 신선하다. */
    static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 4096;

    private static final Logger log = LoggerFactory.getLogger(ControlPlane.class);

    private final ControlStore store;
    private final Leadership leadership;
    private final AllocationRound round;
    private final SnapshotHolder holder;
    private final RedisClock redisClock;
    private final IdlePassCounter idlePasses;
    private final ControlPlaneProperties properties;
    private final ControlMetrics metrics;
    private final AtomicBoolean leaderFailing = new AtomicBoolean();
    private final AtomicBoolean tickFailing = new AtomicBoolean();
    private final AtomicBoolean snapshotFailing = new AtomicBoolean();
    private volatile Disposable loops;

    ControlPlane(ControlStore store, Leadership leadership, AllocationRound round, SnapshotHolder holder,
                 RedisClock redisClock, IdlePassCounter idlePasses, ControlPlaneProperties properties,
                 ControlMetrics metrics) {
        this.store = store;
        this.leadership = leadership;
        this.round = round;
        this.holder = holder;
        this.redisClock = redisClock;
        this.idlePasses = idlePasses;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public void start() {
        loops = Disposables.composite(
                every(properties.leaderRenew(), count -> renewLeadership(), leaderFailing, "리더 리스"),
                every(ControlPlaneProperties.TICK, this::tick, tickFailing, "틱(하트비트 · 배분)"),
                every(properties.snapshotRefresh(), count -> refreshSnapshot(), snapshotFailing, "판정 재료 받기"));
    }

    @Override
    public void stop() {
        Disposable running = loops;
        if (running != null) {
            running.dispose();
        }
        leadership.lose();
        Mono.when(store.releaseLeader(leadership.nodeId()), store.leave(leadership.nodeId()))
                .timeout(Duration.ofSeconds(2))
                .onErrorResume(e -> {
                    log.warn("종료하며 리스 · 분모를 정리하지 못했다 — 수명이 지나면 풀린다: {}", e.toString());
                    return Mono.empty();
                })
                .block();
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public boolean isRunning() {
        Disposable running = loops;
        return running != null && !running.isDisposed();
    }

    Mono<Void> renewLeadership() {
        long requestedAt = leadership.nanoTime();
        return store.acquireLeader(leadership.nodeId(), properties.leaderLease().toMillis())
                .timeout(properties.leaderRenew())
                .doOnNext(lease -> {
                    if (!lease.acquired()) {
                        if (leadership.isLeader()) {
                            log.info("리더를 잃었다 — 지금 리더: {}", lease.owner());
                        }
                        leadership.lose();
                    } else if (leadership.hold(lease.fence(), requestedAt, properties.trustedLease())) {
                        log.info("리더가 됐다 — 임기 {}", lease.fence());
                    }
                })
                // 연장이 늦거나 실패해도 바로 내려놓지 않는다 — 믿는 기간이 끝나면 저절로 리더가 아니다(깜빡임 방지)
                .then();
    }

    Mono<Void> tick(long tickCount) {
        long passes = idlePasses.lastSecond(redisClock.now().getEpochSecond());
        long reapAfter = properties.gatewayReapAfter().toSeconds();
        long fresh = ControlPlaneProperties.TICK.multipliedBy(2).toSeconds();
        return store.heartbeat(leadership.nodeId(), reapAfter, Math.min(fresh, reapAfter), passes)
                .flatMap(view -> {
                    long fence = leadership.fence();
                    if (fence <= 0) {
                        return Mono.empty();
                    }
                    return round.run(fence, view, tickCount)
                            .doOnNext(holder::replace)
                            .onErrorResume(AllocationRound.LostLeadershipException.class, e -> {
                                log.info("임기 {} 의 배분이 거절됐다 — 그 임기를 내려놓는다", fence);
                                leadership.lose(fence);
                                return Mono.empty();
                            })
                            .then();
                });
    }

    Mono<Void> refreshSnapshot() {
        long sentAt = redisClock.localMillis();
        return store.readSnapshot()
                .doOnNext(read -> {
                    redisClock.learn(read.redisNowMillis(), sentAt, redisClock.localMillis());
                    SnapshotCodec.decode(read.entries()).filter(holder::replace).ifPresent(metrics::observe);
                })
                .then();
    }

    private Disposable every(Duration period, LongFunction<Mono<Void>> task, AtomicBoolean failing,
                             String name) {
        return Flux.interval(Duration.ZERO, period)
                .onBackpressureDrop()
                .concatMap(count -> task.apply(count)
                        .timeout(period.multipliedBy(3))
                        .doOnSuccess(done -> {
                            if (failing.compareAndSet(true, false)) {
                                log.info("{} 회복", name);
                            }
                        })
                        .onErrorResume(e -> {
                            metrics.loopFailed(name);
                            if (failing.compareAndSet(false, true)) {
                                log.warn("{} 실패 — 다음 주기에 다시 한다: {}", name, e.toString());
                            }
                            return Mono.empty();
                        }))
                .subscribe();
    }
}
