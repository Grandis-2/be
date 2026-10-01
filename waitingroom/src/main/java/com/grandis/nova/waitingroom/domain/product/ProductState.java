package com.grandis.nova.waitingroom.domain.product;

import java.time.Instant;
import java.util.Objects;

/**
 * 판정에 쓰는 모델 하나의 상태 — credit 은 배분된 초당 입장 인원, cap 은 모델별 초당 상한(회차 잠금이 병목).
 * 불변식은 생성자가 지킨다. 있을 수 없는 상태로 만든 시험은 버그를 증명하지 못한다.
 */
public record ProductState(RuntimeState runtime, long credit, long waiting, long cap, SalesWindow window) {

    public static final long UNLIMITED_CAP = Long.MAX_VALUE;

    public ProductState {
        Objects.requireNonNull(runtime, "runtime 은 필수다");
        Objects.requireNonNull(window, "window 는 필수다");
        if (credit < 0 || waiting < 0) {
            throw new IllegalArgumentException("음수가 될 수 없다: credit=%d, waiting=%d".formatted(credit, waiting));
        }
        if (cap < 1) {
            throw new IllegalArgumentException("모델 상한은 1 이상이어야 한다: " + cap);
        }
        if (credit > cap) {
            throw new IllegalArgumentException("credit 은 모델 상한을 넘지 않는다: credit=%d, cap=%d".formatted(credit, cap));
        }
        // IDLE 은 배분도 줄도 없다는 뜻이다. 갈라지면 "한산할수록 줄로 간다" 는 역전이 생긴다
        if (runtime == RuntimeState.IDLE && (credit != 0 || waiting != 0)) {
            throw new IllegalArgumentException("IDLE 이면 credit · waiting 이 0 이어야 한다: credit=%d, waiting=%d"
                    .formatted(credit, waiting));
        }
        if (runtime == RuntimeState.CLOSED && credit != 0) {
            throw new IllegalArgumentException("CLOSED 면 credit 이 0 이어야 한다: credit=%d".formatted(credit));
        }
        // 줄이 비었는데 대기열 상태라면 유령이다
        if (waiting == 0 && runtime != RuntimeState.IDLE && runtime != RuntimeState.CLOSED) {
            throw new IllegalArgumentException("waiting 이 0 이면 IDLE 또는 CLOSED 여야 한다: runtime=" + runtime);
        }
        if (runtime == RuntimeState.DRAINING && credit < waiting) {
            throw new IllegalArgumentException("DRAINING 이면 credit >= waiting 이어야 한다: credit=%d, waiting=%d"
                    .formatted(credit, waiting));
        }
        if (runtime == RuntimeState.QUEUEING && credit >= waiting) {
            throw new IllegalArgumentException("QUEUEING 이면 credit < waiting 이어야 한다: credit=%d, waiting=%d"
                    .formatted(credit, waiting));
        }
    }

    /** 줄이 없는 모델. 배분을 못 받았으므로 credit 은 0 이다. */
    public static ProductState idle(SalesWindow window, long cap) {
        return new ProductState(RuntimeState.IDLE, 0, 0, cap, window);
    }

    /** 줄이 남은 모델. 이번 틱에 다 뺄 수 있으면 DRAINING, 아니면 QUEUEING 이다. */
    public static ProductState withQueue(long credit, long waiting, SalesWindow window, long cap) {
        if (waiting <= 0) {
            throw new IllegalArgumentException("withQueue 는 줄이 있을 때만이다. 비었으면 idle 을 쓴다: waiting=" + waiting);
        }
        RuntimeState runtime = credit >= waiting ? RuntimeState.DRAINING : RuntimeState.QUEUEING;
        return new ProductState(runtime, credit, waiting, cap, window);
    }

    /** 마감된 모델. 남은 줄은 정리될 때까지 그대로 센다. */
    public static ProductState closed(long waiting, SalesWindow window, long cap) {
        return new ProductState(RuntimeState.CLOSED, 0, waiting, cap, window);
    }

    /** 지금 접수 기간의 어디인가. 리더가 닫았으면 기간과 무관하게 마감이다. */
    public SalesPhase phaseAt(Instant now) {
        return runtime == RuntimeState.CLOSED ? SalesPhase.CLOSED : window.phaseAt(now);
    }

    /**
     * 한산한 모델이 이 노드에서 줄 없이 통과시킬 초당 상한. 이 모델의 credit 으로는 못 잰다(IDLE 이면 0) —
     * 노드 몫 전역 속도에 비율을 곱하고, 모델 상한의 노드 몫(내림)을 넘지 않게 한다. 올리면 노드 합이 상한을 넘는다.
     */
    public long idleCap(SnapshotMeta meta, double idleCreditRatio) {
        if (!Double.isFinite(idleCreditRatio) || idleCreditRatio < 0) {
            throw new IllegalArgumentException("idleCreditRatio 는 0 이상 유한값이어야 한다: " + idleCreditRatio);
        }
        int gateways = meta.effectiveGatewayCount();
        long perNode = meta.globalCredit() / gateways;
        long capped = (long) (perNode * idleCreditRatio);
        // 절삭으로 0 이 되면 아무도 안 몰리는 모델이 전 노드에서 줄을 선다. 비율 0 은 한산 통과를 끈다는 설정이다
        long idle = capped == 0 && perNode > 0 && idleCreditRatio > 0 ? 1 : capped;
        return cap == UNLIMITED_CAP ? idle : Math.min(idle, cap / gateways);
    }

    /** 이번 credit 으로 받아 줄 줄의 최대 길이. credit 0 이면 0 이라 판정기는 최소 속도 폴백과 함께 쓴다. */
    public long queueCapacity(MaxWait maxWait) {
        return maxWait.capacity(credit);
    }
}
