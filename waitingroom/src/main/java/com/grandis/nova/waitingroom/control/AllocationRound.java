package com.grandis.nova.waitingroom.control;

import com.grandis.nova.waitingroom.domain.allocation.FairShareAllocator;
import com.grandis.nova.waitingroom.domain.allocation.Grant;
import com.grandis.nova.waitingroom.domain.allocation.ProductDemand;
import com.grandis.nova.waitingroom.domain.product.ProductState;
import com.grandis.nova.waitingroom.domain.product.SalesPhase;
import com.grandis.nova.waitingroom.domain.product.SnapshotMeta;
import com.grandis.nova.waitingroom.domain.queue.GraceRetention;
import com.grandis.nova.waitingroom.redis.ControlStore;
import com.grandis.nova.waitingroom.redis.ControlStore.ClusterView;
import com.grandis.nova.waitingroom.redis.ControlStore.QueueDepth;
import com.grandis.nova.waitingroom.redis.ControlStore.TimedEntries;
import com.grandis.nova.waitingroom.redis.ProductSchedules;
import com.grandis.nova.waitingroom.redis.ProductSchedules.Schedule;
import com.grandis.nova.waitingroom.redis.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 리더의 배분 회차(틱마다 한 번). 모델 일정 · 운영값 · 줄 길이를 읽어 초당 입장 인원을 나누고, 입장 커서를 올리고,
 * 판정 재료를 발행하고, 이탈자와 마감된 줄을 정리한다. 줄 배분은 전역 속도에서 직전 1초 한산 통과를 뺀 만큼이다.
 */
@Component
class AllocationRound {

    private static final Logger log = LoggerFactory.getLogger(AllocationRound.class);

    private final ControlStore store;
    private final FairShareAllocator allocator = new FairShareAllocator();
    private final AdmissionProperties admission;
    private final ControlPlaneProperties properties;
    private final ControlMetrics metrics;
    private final Leadership leadership;
    private final ScheduleResync resync;
    private final Brake brake;
    /** 이 리더가 모델별로 본 커서 최댓값. Redis 가 커서를 잃으면 되살리는 데 쓴다(값은 늘기만 한다). */
    private final Map<String, Long> writtenMax = new ConcurrentHashMap<>();
    private final Map<String, String> sweepCursors = new ConcurrentHashMap<>();
    /** 이 리더가 줄을 지운 것을 확인한 모델. 시각만으로 은퇴시키면 정리가 실패한 줄이 영영 남는다. */
    private final Set<String> cleaned = ConcurrentHashMap.newKeySet();
    /** 같은 깨진 일정을 틱마다 다시 남기지 않는다. */
    private final Set<String> reportedBroken = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean tidyFailing = new AtomicBoolean();
    private final AtomicBoolean retireFailing = new AtomicBoolean();
    private final AtomicBoolean tombstoneFailing = new AtomicBoolean();

    AllocationRound(ControlStore store, AdmissionProperties admission, ControlPlaneProperties properties,
                    ControlMetrics metrics, Leadership leadership, ScheduleResync resync, Brake brake) {
        this.brake = brake;
        this.resync = resync;
        this.store = store;
        this.admission = admission;
        this.properties = properties;
        this.metrics = metrics;
        this.leadership = leadership;
    }

    /** @return 옛 임기라 막혔으면 {@link LostLeadershipException} 으로 끝난다 */
    Mono<GatewaySnapshot> run(long fence, ClusterView cluster, long tick) {
        return Mono.zip(store.readProducts(), store.readSettings())
                .flatMap(read -> {
                    TimedEntries products = read.getT1();
                    Instant readAt = Instant.ofEpochMilli(products.redisNowMillis());
                    OperationalSettings settings = OperationalSettings.from(read.getT2(), admission.globalCredit(),
                            admission.productCap());
                    Map<String, Schedule> schedules = schedules(products.entries());
                    // 쓸 수 있는 일정이 하나도 없으면(비었거나 깨진 값 · 은퇴 표식만) 놓친 회차가 있을 수 있다
                    if (schedules.isEmpty()) {
                        resync.requestIfQuiet();
                    } else {
                        resync.known();
                    }
                    writtenMax.keySet().retainAll(schedules.keySet());
                    sweepCursors.keySet().retainAll(schedules.keySet());
                    cleaned.retainAll(schedules.keySet());
                    return Flux.fromIterable(schedules.keySet())
                            .concatMap(key -> retired(key, schedules.get(key), readAt)
                                    ? Mono.just(new Row(key, schedules.get(key), RETIRED))
                                    : store.depth(key).map(depth -> new Row(key, schedules.get(key), depth)))
                            .collectList()
                            .flatMap(rows -> allocate(fence, cluster, tick, products.redisNowMillis(), settings, rows))
                            .flatMap(snapshot -> dropOldTombstones(products.entries(), readAt).thenReturn(snapshot));
                });
    }

    private Mono<GatewaySnapshot> allocate(long fence, ClusterView cluster, long tick, long nowMillis,
                                           OperationalSettings settings, List<Row> rows) {
        Instant now = Instant.ofEpochMilli(nowMillis);
        return brakeFactor(fence, cluster, now)
                .flatMap(factor -> allocate(fence, cluster, tick, nowMillis, settings, rows, factor));
    }

    /** 새 임기면 Redis 에 발행된 판정 재료의 배율에서 이어 간다 — 이 노드가 아직 재료를 못 받았어도 풀지 않게. */
    private Mono<Double> brakeFactor(long fence, ClusterView cluster, Instant now) {
        Mono<Void> adopted = !brake.needsAdopt(fence) ? Mono.empty() : store.readSnapshot()
                .map(read -> SnapshotCodec.decode(read.entries()).map(GatewaySnapshot::brakeFactor).orElse(1.0))
                .doOnNext(factor -> brake.adopt(fence, factor, now))
                .then();
        return adopted.then(Mono.fromCallable(() -> brake.next(cluster.relayTotals(), now)));
    }

    private Mono<GatewaySnapshot> allocate(long fence, ClusterView cluster, long tick, long nowMillis,
                                           OperationalSettings settings, List<Row> rows, double brakeFactor) {
        Instant now = Instant.ofEpochMilli(nowMillis);
        List<ProductDemand> demands = rows.stream()
                .map(row -> new ProductDemand(row.key(), row.depth().waiting(), settings.capOf(row.key()),
                        row.schedule().visible() && row.schedule().window().phaseAt(now) == SalesPhase.OPEN))
                .toList();
        // 브레이크는 운영값을 줄이기만 한다. 노드들이 쓰는 전역 속도(meta)도 줄인 값이다
        long globalCredit = Brake.apply(settings.globalCredit(), brakeFactor);
        long pool = Math.max(0, globalCredit - cluster.idlePassSum());
        Map<String, Long> grants = allocator.allocate(pool, demands, tick).stream()
                .collect(Collectors.toMap(Grant::productKey, Grant::credit));

        return Flux.fromIterable(rows)
                .concatMap(row -> admit(fence, row, grants.getOrDefault(row.key(), 0L), tick)
                        .map(entered -> state(row, settings, grants.getOrDefault(row.key(), 0L), entered, now)))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .flatMap(states -> {
                    GatewaySnapshot snapshot = new GatewaySnapshot(states,
                            new SnapshotMeta(globalCredit, cluster.aliveGateways(), settings.maxWait()), nowMillis, brakeFactor);
                    return store.publishSnapshot(fence, properties.fenceTtl().toMillis(), SnapshotCodec.encode(snapshot))
                            .flatMap(published -> published ? Mono.just(snapshot) : Mono.error(new LostLeadershipException()));
                })
                .flatMap(snapshot -> tidy(fence, rows, now).then(retireFinished(rows, now)).thenReturn(snapshot));
    }

    /**
     * 끝난 지 오래된 모델을 배분에서 빼 은퇴 표식(일정 번호만)으로 바꾼다 — 일정 목록과 판정 재료가 커지기만 하지 않게.
     * 그 전까지는 진입에 404 가 아니라 마감으로 답한다. 실패해도 다음 회차에 다시 한다.
     */
    private Mono<Void> retireFinished(List<Row> rows, Instant now) {
        return Flux.fromIterable(rows)
                .filter(row -> row.depth() == RETIRED && !now.isBefore(row.schedule().window().closesAt().plus(FORGET_AFTER)))
                .concatMap(row -> store.retireSchedule(row.key(), row.schedule().format(),
                                row.schedule().scheduleVersion(), now)
                        .doOnNext(retired -> changed("retire", row.key(), retired)))
                .then()
                .doOnSuccess(done -> recovered(retireFailing, "끝난 모델 은퇴"))
                .onErrorResume(e -> failed(retireFailing, "끝난 모델 은퇴", e));
    }

    /** 은퇴 표식은 큐가 옛 메시지를 다시 줄 수 있는 동안만 둔다. 그 뒤에는 막을 재전달이 없다. */
    private Mono<Void> dropOldTombstones(Map<String, String> raw, Instant now) {
        return Flux.fromIterable(raw.entrySet())
                .filter(entry -> ProductSchedules.retiredAt(entry.getValue())
                        .filter(at -> !now.isBefore(at.plus(TOMBSTONE_TTL))).isPresent())
                .concatMap(entry -> store.dropSchedule(entry.getKey(), entry.getValue())
                        .doOnNext(dropped -> changed("drop", entry.getKey(), dropped)))
                .then()
                .doOnSuccess(done -> recovered(tombstoneFailing, "은퇴 표식 정리"))
                .onErrorResume(e -> failed(tombstoneFailing, "은퇴 표식 정리", e));
    }

    /**
     * 조건부 변경의 결과. 바꿨으면 센다. 못 바꿨으면 읽은 뒤 새 일정이 왔거나 이미 없는 것이라 바꾸지 않는 것이
     * 맞다 — 성공으로 세지 않고 충돌로 남기며, 다음 회차가 최신 값으로 다시 판단한다.
     */
    private void changed(String work, String productKey, boolean applied) {
        metrics.scheduleCleanup(work, applied);
        if (!applied) {
            log.debug("{} 건너뜀 — 읽은 뒤 일정이 바뀌었다 productId={}", work, productKey);
        }
    }

    /** 다음 회차에 다시 하므로 배분을 막지 않는다. 작업마다 상태가 바뀔 때만 원인을 남긴다. */
    private Mono<Void> failed(AtomicBoolean failing, String work, Throwable e) {
        metrics.loopFailed("retire");
        if (failing.compareAndSet(false, true)) {
            log.warn("{} 실패 — 다음 회차에 다시 한다: {}", work, e.toString());
        }
        return Mono.empty();
    }

    private static void recovered(AtomicBoolean failing, String work) {
        if (failing.compareAndSet(true, false)) {
            log.info("{} 회복", work);
        }
    }

    /**
     * 몫만큼 입장 커서를 올린다(회차는 틱 번호 — 같은 틱을 재시도해도 한 번만 나간다). 몫이 0 이어도 Redis 커서가
     * 이 리더가 본 값보다 뒤면 부른다 — 되살리지 않으면 입장한 사람이 대기로 돌아간다. 옛 임기로 막히면 회차를 멈춘다.
     */
    private Mono<Long> admit(long fence, Row row, long credit, long round) {
        if (row.depth() == RETIRED) {
            return Mono.just(0L);
        }
        long seen = writtenMax.getOrDefault(row.key(), -1L);
        if (credit <= 0 && seen <= row.depth().cursor()) {
            return Mono.just(0L);
        }
        if (leadership.fence() != fence) {
            return Mono.error(new LostLeadershipException());
        }
        return store.apply(row.key(), credit, fence, properties.fenceTtl().toMillis(), writtenMax.getOrDefault(row.key(), -1L),
                        round)
                .flatMap(result -> {
                    if (result.fenced()) {
                        return Mono.error(new LostLeadershipException());
                    }
                    writtenMax.merge(row.key(), result.cursor(), Math::max);
                    reportedBroken.remove(APPLY_FAILED + row.key());
                    metrics.admitted(row.key(), result.entered());
                    return Mono.just(result.entered());
                })
                // 한 모델의 커서가 깨져도(손으로 고친 값 등) 나머지 모델의 배분과 발행은 돈다
                .onErrorResume(e -> !(e instanceof LostLeadershipException), e -> {
                    metrics.loopFailed("apply");
                    if (reportedBroken.add(APPLY_FAILED + row.key())) {
                        log.warn("모델 {} 의 입장 커서를 올리지 못해 이번 회차는 건너뛴다: {}", row.key(), e.toString());
                    }
                    return Mono.just(0L);
                });
    }

    private Map.Entry<String, ProductState> state(Row row, OperationalSettings settings, long credit, long entered,
                                                  Instant now) {
        long waiting = Math.max(0, row.depth().waiting() - entered);
        long cap = settings.capOf(row.key());
        ProductState state;
        if (row.schedule().window().phaseAt(now) == SalesPhase.CLOSED) {
            state = ProductState.closed(waiting, row.schedule().window(), cap);
        } else if (!row.schedule().visible()) {
            state = ProductState.hidden(waiting, row.schedule().window(), cap);
        } else if (waiting > 0) {
            state = ProductState.withQueue(credit, waiting, row.schedule().window(), cap);
        } else {
            state = ProductState.idle(row.schedule().window(), cap);
        }
        return Map.entry(row.key(), state);
    }

    /**
     * 이탈자 청소와 마감된 줄 정리. 실패해도 다음 회차에 다시 하므로 배분 결과를 막지 않고, 한 모델이 실패해도
     * 뒤 모델은 정리한다. 줄 길이 읽기는 격리하지 않는다 — 빼고 발행하면 그 모델이 "없는 상품"이 된다.
     */
    private Mono<Void> tidy(long fence, List<Row> rows, Instant now) {
        AtomicBoolean failed = new AtomicBoolean();
        return Flux.fromIterable(rows)
                .filter(row -> row.depth() != RETIRED)
                .concatMap(row -> sweep(fence, row, now).then(close(fence, row, now))
                        .onErrorResume(e -> {
                            failed.set(true);
                            metrics.loopFailed("tidy");
                            if (!tidyFailing.get()) {
                                log.warn("모델 {} 의 이탈자 청소 · 마감 정리 실패 — 다음 회차에 다시 한다: {}", row.key(), e.toString());
                            }
                            return Mono.empty();
                        }))
                .then(Mono.fromRunnable(() -> {
                    if (tidyFailing.getAndSet(failed.get()) && !failed.get()) {
                        log.info("이탈자 청소 · 마감 정리 회복");
                    }
                }));
    }

    private Mono<Void> sweep(long fence, Row row, Instant now) {
        return store.sweep(row.key(), properties.sweepScanLimit(), now.getEpochSecond(), GraceRetention.SECONDS,
                        properties.sweepBudget(), sweepCursors.getOrDefault(row.key(), "0"), true, fence)
                .doOnNext(result -> sweepCursors.put(row.key(), result.nextCursor()))
                .then();
    }

    private Mono<Void> close(long fence, Row row, Instant now) {
        Instant closesAt = row.schedule().window().closesAt();
        if (now.isBefore(closesAt)) {
            return Mono.empty();
        }
        long deletableAt = closesAt.plus(properties.closeGrace()).getEpochSecond();
        return store.closeQueue(row.key(), fence, true, properties.fenceTtl().toMillis(), deletableAt)
                .doOnNext(result -> {
                    if (result == CLOSED) {
                        cleaned.add(row.key());
                    }
                })
                .then();
    }

    /** 깨진 일정과 모양이 틀린 모델 키는 빼고(처음 볼 때 한 번만 남긴다), 나머지는 키 순서로 고정한다. */
    private Map<String, Schedule> schedules(Map<String, String> raw) {
        Map<String, Schedule> schedules = new TreeMap<>();
        raw.forEach((key, value) -> {
            if (ProductSchedules.isRetired(value)) {
                return;
            }
            Optional<Schedule> parsed = RedisKeys.validProductKey(key) ? ProductSchedules.parse(value) : Optional.empty();
            if (parsed.isPresent()) {
                schedules.put(key, parsed.get());
                reportedBroken.remove(key + "=" + value);
            } else if (reportedBroken.add(key + "=" + value)) {
                log.warn("배분 대상 모델 {} 의 키나 접수 일정이 잘못돼 뺀다", key);
            }
        });
        return schedules;
    }

    /**
     * 이탈 기록 보관까지 지났고 줄을 지운 것도 확인한 모델. Redis 를 치지 않고 마감 상태만 발행한다 — 누적된 모델
     * 수만큼 틱 비용이 늘지 않게. 마감 상태로는 계속 남겨 진입이 404 가 아니라 마감으로 답한다.
     */
    private boolean retired(String key, Schedule schedule, Instant now) {
        Instant done = schedule.window().closesAt().plus(properties.closeGrace()).plusSeconds(GraceRetention.SECONDS * 2);
        return cleaned.contains(key) && !now.isBefore(done);
    }

    private static final QueueDepth RETIRED = new QueueDepth(0, -1);
    /** 마감 뒤 이만큼 지나면 배분에서 빼 은퇴 표식만 남긴다. */
    static final Duration FORGET_AFTER = Duration.ofDays(7);
    /** 은퇴 표식을 두는 기간. SQS 가 메시지를 붙들 수 있는 최대 기간(14일)과 같다. */
    static final Duration TOMBSTONE_TTL = Duration.ofDays(14);
    private static final String APPLY_FAILED = "apply:";
    private static final long CLOSED = 1;

    private record Row(String key, Schedule schedule, QueueDepth depth) {
    }

    /** 옛 임기라 Redis 가 쓰기를 거절했다. 새 리더가 이미 있다. */
    static final class LostLeadershipException extends RuntimeException {

        LostLeadershipException() {
            super("옛 임기라 거절됐다", null, false, false);
        }
    }
}
