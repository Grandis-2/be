package com.grandis.nova.waitingroom.control;

import java.util.Optional;

/**
 * 리더가 전역 속도를 정할 때 본 값. 모든 노드가 지표 · 관리자 화면에 같은 값을 보이도록 판정 재료에 싣는다.
 *
 * @param operationalCredit 운영값(용량 상한 · 브레이크 전). 이 칸이 없는 옛 재료면 {@link #UNKNOWN}
 * @param sum               preorder 용량 합. 표시가 없거나 읽지 못하면 {@link #UNKNOWN}
 * @param ready             다 뜬 preorder 태스크 수
 * @param warming           갓 뜬 preorder 태스크 수
 * @param reachable         preorder 를 최대 대수로 늘리면 닿는 용량. 모르면 {@link #UNKNOWN}
 * @param held              직전 값을 그대로 쓰는 중이다 — 배분에는 쓰지만 지금 잰 값은 아니다
 */
public record CapacityStatus(long operationalCredit, long sum, int ready, int warming, long reachable, boolean held) {

    public static final long UNKNOWN = -1;
    /** 이 칸을 모르는 옛 리더가 낸 재료. 운영값도 용량도 모른다. */
    public static final CapacityStatus MISSING = new CapacityStatus(UNKNOWN, UNKNOWN, 0, 0, UNKNOWN, false);

    static CapacityStatus of(long operationalCredit, Optional<PreorderCapacity> capacity) {
        return capacity.map(found -> new CapacityStatus(operationalCredit, found.sum(), found.ready(), found.warming(),
                        found.reachable(), found.held()))
                .orElseGet(() -> unknown(operationalCredit));
    }

    public static CapacityStatus unknown(long operationalCredit) {
        return new CapacityStatus(operationalCredit, UNKNOWN, 0, 0, UNKNOWN, false);
    }

    public boolean known() {
        return sum != UNKNOWN;
    }

    /** 이번 회차에 잰 용량이다. 관리자 기준은 이것만 쓴다. */
    public boolean measured() {
        return known() && !held;
    }

    public boolean operationalKnown() {
        return operationalCredit != UNKNOWN;
    }
}
