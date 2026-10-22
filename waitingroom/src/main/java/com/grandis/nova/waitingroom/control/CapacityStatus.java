package com.grandis.nova.waitingroom.control;

import java.util.Optional;

/**
 * 리더가 전역 속도를 정할 때 본 값. 모든 노드가 지표 · 관리자 화면에 같은 값을 보이도록 판정 재료에 싣는다.
 *
 * @param operationalCredit 운영값(용량 상한 · 브레이크 전). 이 칸이 없는 옛 재료면 {@link #UNKNOWN}
 * @param sum               preorder 용량 합. 표시가 없거나 읽지 못하면 {@link #UNKNOWN}
 * @param ready             다 뜬 preorder 태스크 수
 * @param warming           갓 뜬 preorder 태스크 수
 */
public record CapacityStatus(long operationalCredit, long sum, int ready, int warming) {

    public static final long UNKNOWN = -1;
    /** 이 칸을 모르는 옛 리더가 낸 재료. 운영값도 용량도 모른다. */
    public static final CapacityStatus MISSING = new CapacityStatus(UNKNOWN, UNKNOWN, 0, 0);

    static CapacityStatus of(long operationalCredit, Optional<PreorderCapacity> capacity) {
        return capacity.map(found -> new CapacityStatus(operationalCredit, found.sum(), found.ready(), found.warming()))
                .orElseGet(() -> unknown(operationalCredit));
    }

    public static CapacityStatus unknown(long operationalCredit) {
        return new CapacityStatus(operationalCredit, UNKNOWN, 0, 0);
    }

    public boolean known() {
        return sum != UNKNOWN;
    }

    public boolean operationalKnown() {
        return operationalCredit != UNKNOWN;
    }
}
