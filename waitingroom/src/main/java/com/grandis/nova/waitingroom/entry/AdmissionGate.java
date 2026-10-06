package com.grandis.nova.waitingroom.entry;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.ErrorCode;
import com.grandis.nova.waitingroom.WaitingroomErrorCode;
import com.grandis.nova.waitingroom.control.AdmissionProperties;
import com.grandis.nova.waitingroom.control.ControlPlaneProperties;
import com.grandis.nova.waitingroom.control.GatewaySnapshot;
import com.grandis.nova.waitingroom.control.IdlePassCounter;
import com.grandis.nova.waitingroom.control.RedisClock;
import com.grandis.nova.waitingroom.control.SnapshotHolder;
import com.grandis.nova.waitingroom.domain.admission.AdmissionDecider;
import com.grandis.nova.waitingroom.domain.admission.AdmissionDecision;
import com.grandis.nova.waitingroom.domain.admission.AdmissionRequest;
import com.grandis.nova.waitingroom.domain.admission.EnqueueLatch;
import com.grandis.nova.waitingroom.domain.admission.SecondWindowLimiter;
import com.grandis.nova.waitingroom.domain.product.ProductState;
import com.grandis.nova.waitingroom.domain.product.RuntimeState;
import com.grandis.nova.waitingroom.domain.product.SalesPhase;
import com.grandis.nova.waitingroom.domain.product.SnapshotMeta;
import com.grandis.nova.waitingroom.domain.queue.AdmissionTicket;
import com.grandis.nova.waitingroom.domain.queue.EtaPolicy;
import com.grandis.nova.waitingroom.domain.queue.PollIntervalPolicy;
import com.grandis.nova.waitingroom.domain.queue.QueueEntry;
import com.grandis.nova.waitingroom.domain.queue.QueueToken;
import com.grandis.nova.waitingroom.redis.QueueStore;
import com.grandis.nova.waitingroom.redis.QueueStore.QueueStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;

/**
 * 진입 판정. 한산하면 Redis 없이 입장권을 주고, 몰리면 줄에 세운다. 이미 입장한 사람은 같은 입장권을 다시 받는다 —
 * 입장 한 건에 입장권 한 장이다. 판정 재료가 없거나 Redis 가 안 되면 통과시키지 않고 503 이다.
 */
@Component
public class AdmissionGate {

    private static final Logger log = LoggerFactory.getLogger(AdmissionGate.class);

    /** 리미터 · 래치가 기억하는 모델 수의 상한. 요청에서 온 키라 묶어 둔다. */
    private static final int MAX_KEYS = 10_000;

    private final SnapshotHolder snapshots;
    private final RedisClock clock;
    private final IdlePassCounter idlePasses;
    private final QueueStore queue;
    private final AdmissionTicket tickets;
    private final QueueToken queueTokens;
    private final ProductClosures closures;
    private final EntryMetrics metrics;
    private final AdmissionDecider decider;
    private final PollIntervalPolicy polls = PollIntervalPolicy.standard();
    /** 방금 줄로 보낸 모델 · 방금 가득 찬 모델. 판정 재료가 한 틱 늦는 동안 뒤에 온 사람이 앞지르지 않게 메운다. */
    private final EnqueueLatch enqueued;
    private final EnqueueLatch full;

    AdmissionGate(SnapshotHolder snapshots, RedisClock clock, IdlePassCounter idlePasses, QueueStore queue,
                  AdmissionTicket tickets, QueueToken queueTokens, ProductClosures closures, EntryMetrics metrics,
                  AdmissionProperties admission, ControlPlaneProperties control) {
        this.snapshots = snapshots;
        this.clock = clock;
        this.idlePasses = idlePasses;
        this.queue = queue;
        this.tickets = tickets;
        this.queueTokens = queueTokens;
        this.closures = closures;
        this.metrics = metrics;
        this.decider = new AdmissionDecider(new SecondWindowLimiter(MAX_KEYS), admission.idleCreditRatio());
        Duration lag = ControlPlaneProperties.TICK.plus(control.snapshotRefresh());
        this.enqueued = EnqueueLatch.covering(MAX_KEYS, lag);
        this.full = EnqueueLatch.covering(MAX_KEYS, lag);
    }

    public Mono<QueueView> enter(String productKey, String customerId) {
        return Mono.defer(() -> {
            GatewaySnapshot snapshot = snapshots.current().orElseThrow(() -> rejected(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
            ProductState state = snapshot.product(productKey)
                    .orElseThrow(() -> rejected(WaitingroomErrorCode.PRODUCT_NOT_FOUND));
            if (closures.closed(productKey, state.window())) {
                throw rejected(WaitingroomErrorCode.SALE_CLOSED);
            }
            Instant now = clock.now();
            long second = now.getEpochSecond();
            if (state.runtime() == RuntimeState.HIDDEN) {
                // 판정 재료는 한 틱 늦다 — 그사이 마감됐으면 숨김보다 마감이 앞이다
                if (state.phaseAt(now) == SalesPhase.CLOSED) {
                    throw rejection(AdmissionDecision.REJECT_CLOSED, state);
                }
                return rejoinHidden(productKey, customerId, state, snapshot.meta(), now, second);
            }
            AdmissionDecision decision = decider.decide(new AdmissionRequest(productKey, state, snapshot.meta(), now,
                    snapshots.stale(), enqueued.latched(productKey, second), full.latched(productKey, second)));
            metrics.decision(decision);
            log.debug("진입 판정 customerId={} productId={} decision={}", customerId, productKey, decision);
            if (decision.isPass()) {
                idlePasses.record(second);
                return Mono.just(admitted(productKey, customerId, now, now));
            }
            if (decision == AdmissionDecision.REJECT_QUEUE_FULL) {
                // 새로 오는 사람만 막는다. 이미 줄에 섰거나 입장한 사람은 상한 0 으로 물어 자리 · 입장권을 그대로 받는다
                full.mark(productKey, second);
                return placeIn(productKey, customerId, state, snapshot.meta(), now, second, 0);
            }
            if (decision.isReject()) {
                throw rejection(decision, state);
            }
            enqueued.mark(productKey, second);
            return placeIn(productKey, customerId, state, snapshot.meta(), now, second, maxLength(state, snapshot.meta()));
        }).doOnNext(view -> metrics.entry(view.status()))
                .doOnError(BusinessException.class, e -> metrics.entry(e.errorCode().name()));
    }

    /**
     * 비공개 상품은 없는 상품처럼 답한다. 다만 이미 선 사람 · 입장한 사람은 상한 0 으로 물어 자리 · 입장권을 그대로 준다 —
     * 새로 고침한 사람이 자리를 잃지 않게.
     */
    private Mono<QueueView> rejoinHidden(String productKey, String customerId, ProductState state, SnapshotMeta meta,
                                         Instant now, long second) {
        return queue.enqueue(productKey, customerId, 0, now)
                .onErrorMap(AdmissionGate::storeFailure, AdmissionGate::unavailable)
                .map(placed -> {
                    if (placed.admittedAt() == null && !placed.entry().accepted()) {
                        throw rejected(WaitingroomErrorCode.PRODUCT_NOT_FOUND);
                    }
                    return placed(productKey, customerId, state, meta, now, second, placed);
                });
    }

    private Mono<QueueView> placeIn(String productKey, String customerId, ProductState state, SnapshotMeta meta,
                                    Instant now, long second, long maxLength) {
        return queue.enqueue(productKey, customerId, maxLength, now)
                .onErrorMap(AdmissionGate::storeFailure, AdmissionGate::unavailable)
                .map(placed -> placed(productKey, customerId, state, meta, now, second, placed));
    }

    private QueueView placed(String productKey, String customerId, ProductState state, SnapshotMeta meta, Instant now,
                             long second, QueueStatus placed) {
        if (placed.admittedAt() != null) {
            return admitted(productKey, customerId, placed.admittedAt(), now);
        }
        QueueEntry entry = placed.entry();
        if (!entry.accepted()) {
            full.mark(productKey, second);
            throw rejected(WaitingroomErrorCode.QUEUE_FULL);
        }
        double eta = EtaCredit.etaSec(entry.rank(), state, meta);
        return new QueueView.Waiting(queueTokens.issue(productKey, customerId, now), entry.rank() + 1,
                EtaPolicy.reportSec(eta), null, null, entry.alreadyQueued(),
                polls.intervalSec(eta, ThreadLocalRandom.current()::nextDouble));
    }

    /** 입장 시각으로 낸다 — 같은 입장이면 언제 물어도 같은 입장권이다. */
    QueueView.Admitted admitted(String productKey, String customerId, Instant admittedAt, Instant now) {
        long expiresIn = Duration.between(now, tickets.expiresAt(admittedAt)).toSeconds();
        return new QueueView.Admitted(tickets.issue(productKey, customerId, admittedAt), Math.max(0, expiresIn));
    }

    private long maxLength(ProductState state, SnapshotMeta meta) {
        long capacity = decider.queueCapacity(state, meta.maxWait());
        return capacity == Long.MAX_VALUE ? QueueStore.UNLIMITED : capacity;
    }

    private static BusinessException rejection(AdmissionDecision decision, ProductState state) {
        return switch (decision) {
            case REJECT_NOT_OPEN -> new BusinessException(WaitingroomErrorCode.SALE_NOT_OPEN,
                    Map.of("reason", "opensAt=" + state.window().opensAt()));
            case REJECT_CLOSED -> new BusinessException(WaitingroomErrorCode.SALE_CLOSED,
                    Map.of("reason", "closesAt=" + state.window().closesAt()));
            default -> rejected(WaitingroomErrorCode.QUEUE_FULL);
        };
    }

    static BusinessException rejected(ErrorCode code) {
        return new BusinessException(code);
    }

    /** Redis 장애(연결 · 시한 · 명령 오류)만 503 으로 바꾼다. 응답 해석 실패 같은 버그는 그대로 500 으로 드러낸다. */
    static boolean storeFailure(Throwable e) {
        return e instanceof DataAccessException || e instanceof TimeoutException;
    }

    /** Redis 장애. 요청마다 쌓이므로 지표로 보고 로그는 낮춘다. */
    static BusinessException unavailable(Throwable cause) {
        log.debug("대기열 저장소를 쓰지 못했다: {}", cause.toString());
        return rejected(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
    }
}
