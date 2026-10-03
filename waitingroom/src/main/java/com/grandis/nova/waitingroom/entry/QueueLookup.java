package com.grandis.nova.waitingroom.entry;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.waitingroom.WaitingroomErrorCode;
import com.grandis.nova.waitingroom.control.GatewaySnapshot;
import com.grandis.nova.waitingroom.control.RedisClock;
import com.grandis.nova.waitingroom.control.SnapshotHolder;
import com.grandis.nova.waitingroom.domain.product.ProductState;
import com.grandis.nova.waitingroom.domain.product.SalesPhase;
import com.grandis.nova.waitingroom.domain.queue.EtaPolicy;
import com.grandis.nova.waitingroom.domain.queue.PollIntervalPolicy;
import com.grandis.nova.waitingroom.domain.queue.QueueEntry;
import com.grandis.nova.waitingroom.domain.queue.QueueToken;
import com.grandis.nova.waitingroom.redis.QueueStore;
import com.grandis.nova.waitingroom.redis.QueueStore.QueueStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 순서 조회. 조회가 곧 생존 신호라 줄에 남으려면 알려 준 간격으로 계속 물어야 한다. 대기 토큰이 무효하거나 남의 것이면
 * 줄에 없다고만 답한다 — 어느 칸이 틀렸는지 알려 주지 않는다.
 */
@Component
public class QueueLookup {

    private final SnapshotHolder snapshots;
    private final RedisClock clock;
    private final QueueStore queue;
    private final QueueToken queueTokens;
    private final ProductClosures closures;
    private final AdmissionGate gate;
    private final EntryMetrics metrics;
    private final PollIntervalPolicy polls = PollIntervalPolicy.standard();

    QueueLookup(SnapshotHolder snapshots, RedisClock clock, QueueStore queue, QueueToken queueTokens,
                ProductClosures closures, AdmissionGate gate, EntryMetrics metrics) {
        this.snapshots = snapshots;
        this.clock = clock;
        this.queue = queue;
        this.queueTokens = queueTokens;
        this.closures = closures;
        this.gate = gate;
        this.metrics = metrics;
    }

    public Mono<QueueView> status(String productKey, String customerId, String queueToken) {
        return Mono.defer(() -> {
            GatewaySnapshot snapshot = snapshots.current()
                    .orElseThrow(() -> AdmissionGate.rejected(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
            ProductState state = snapshot.product(productKey)
                    .orElseThrow(() -> AdmissionGate.rejected(WaitingroomErrorCode.PRODUCT_NOT_FOUND));
            Instant now = clock.now();
            if (state.phaseAt(now) == SalesPhase.CLOSED || closures.closed(productKey, state.window())) {
                return Mono.just(closed(QueueView.Closed.Reason.SALE_CLOSED));
            }
            boolean owner = queueTokens.verify(queueToken, productKey, now).filter(customerId::equals).isPresent();
            if (!owner) {
                return Mono.just(closed(QueueView.Closed.Reason.NOT_IN_QUEUE));
            }
            return queue.status(productKey, customerId, now)
                    .onErrorMap(AdmissionGate::storeFailure, AdmissionGate::unavailable)
                    .map(found -> view(productKey, customerId, state, now, found));
        }).doOnNext(view -> metrics.status(view instanceof QueueView.Closed closed ? closed.reason().name() : view.status()))
                .doOnError(BusinessException.class, e -> metrics.status(e.errorCode().name()));
    }

    private QueueView view(String productKey, String customerId, ProductState state, Instant now, QueueStatus found) {
        QueueEntry entry = found.entry();
        return switch (entry.state()) {
            case ADMITTED -> gate.admitted(productKey, customerId, found.admittedAt(), now);
            case WAITING -> {
                double eta = EtaPolicy.etaSec(entry.rank(), state.credit());
                long behind = entry.behind();
                yield new QueueView.Waiting(null, entry.rank() + 1, EtaPolicy.reportSec(eta),
                        entry.total() == QueueEntry.UNKNOWN_TOTAL ? null : entry.total(),
                        behind == QueueEntry.UNKNOWN_TOTAL ? null : behind, null,
                        polls.intervalSec(eta, ThreadLocalRandom.current()::nextDouble));
            }
            case NOT_QUEUED, REJECTED -> closed(QueueView.Closed.Reason.NOT_IN_QUEUE);
        };
    }

    private static QueueView closed(QueueView.Closed.Reason reason) {
        return new QueueView.Closed(reason);
    }
}
