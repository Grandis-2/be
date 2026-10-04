package com.grandis.nova.waitingroom.domain.admission;

import com.grandis.nova.waitingroom.domain.admission.SecondWindowLimiter.AcquireResult;
import com.grandis.nova.waitingroom.domain.product.MaxWait;
import com.grandis.nova.waitingroom.domain.product.ProductState;
import com.grandis.nova.waitingroom.domain.product.RuntimeState;

/**
 * 진입 판정. 순서가 곧 정책이다 — 위에서부터 처음 걸리는 줄이 답이다.
 * 어떤 경우에도 줄 선 사람을 추월시키지 않는다. 상태를 모르는 것은 추월의 사유가 아니다.
 */
public class AdmissionDecider {

    /** 배수 속도를 모를 때 가정하는 초당 인원. 배분이 줄 수 있는 0 이 아닌 가장 작은 몫이다. */
    public static final long MIN_CREDIT = 1;

    private static final String GLOBAL_KEY = "node:";
    private static final String PRODUCT_KEY_PREFIX = "product:";

    private final SecondWindowLimiter limiter;
    private final double idleCreditRatio;

    public AdmissionDecider(SecondWindowLimiter limiter, double idleCreditRatio) {
        if (limiter == null) {
            throw new IllegalArgumentException("limiter 는 필수다");
        }
        if (!Double.isFinite(idleCreditRatio) || idleCreditRatio < 0) {
            throw new IllegalArgumentException("idleCreditRatio 는 0 이상 유한값이어야 한다: " + idleCreditRatio);
        }
        this.limiter = limiter;
        this.idleCreditRatio = idleCreditRatio;
    }

    public AdmissionDecision decide(AdmissionRequest request) {
        ProductState state = request.state();

        // 1 — 접수 기간 밖이면 나머지를 볼 필요가 없다. 맨 앞이어야 닫힌 모델이 예산을 갉아먹지 않는다
        switch (state.phaseAt(request.now())) {
            case BEFORE_OPEN -> {
                return AdmissionDecision.REJECT_NOT_OPEN;
            }
            case CLOSED -> {
                return AdmissionDecision.REJECT_CLOSED;
            }
            case OPEN -> {
            }
        }

        // 2 — 줄이 받아 줄 길이를 넘었다. 줄로 보내는 모든 줄보다 앞이다. 줄 세우기와 같은 상한으로 잰다 —
        //     나머지 배분으로 이번 틱만 credit 0 인 모델이 있어, 폴백 없이 재면 그 틱에 전원이 거절된다
        if (request.staleFull() || (state.waiting() > 0
                && state.waiting() >= queueCapacity(state, request.meta().maxWait()))) {
            return AdmissionDecision.REJECT_QUEUE_FULL;
        }

        // 3 — 재료가 낡았다. 비어 보여도 낡은 뒤에 선 줄을 모르므로 줄에 세운다
        if (request.dataStale()) {
            return AdmissionDecision.ENQUEUE_STALE;
        }

        // 4 — 이미 붐빈다. 래치는 스냅샷이 따라잡기 전 한 틱을 메운다
        if (state.runtime() != RuntimeState.IDLE || request.justEnqueued()) {
            return AdmissionDecision.ENQUEUE_BACKLOG;
        }

        // 5 — 안 몰려도 무제한은 아니다. 모델 몫과 노드 몫을 함께 차감한다
        AcquireResult acquired = limiter.tryAcquireAll(
                PRODUCT_KEY_PREFIX + request.productKey(), state.idleCap(request.meta(), idleCreditRatio),
                GLOBAL_KEY, request.meta().globalCapPerNode(), request.epochSecond());
        return switch (acquired) {
            case ACQUIRED -> AdmissionDecision.PASS_UNDER_CAP;
            case PRODUCT_EXHAUSTED -> AdmissionDecision.ENQUEUE_RATE_PRODUCT;
            case GLOBAL_EXHAUSTED -> AdmissionDecision.ENQUEUE_RATE_GLOBAL;
            case KEY_SATURATED -> AdmissionDecision.ENQUEUE_KEY_SATURATED;
        };
    }

    /**
     * 줄 세우기 경로가 쓰는 줄 상한. 배수 속도를 모르는 것(credit 0)과 자리가 없는 것은 다르다 —
     * 모르면 가장 낮은 속도를 가정한다. 0 을 쓰면 줄이 한 번도 안 생긴다.
     */
    public long queueCapacity(ProductState state, MaxWait maxWait) {
        long byCredit = state.queueCapacity(maxWait);
        return byCredit > 0 ? byCredit : maxWait.capacity(MIN_CREDIT);
    }
}
