package com.grandis.nova.preorder.scaling;

import com.grandis.nova.preorder.campaign.ScalingPlan;

import java.util.List;

/**
 * 지금 맞출 최소 대수. 회차마다 따로 정하지 않고 지금 걸린 회차들 중 가장 큰 값을 쓴다 — 겹친 회차 하나가 끝나도 다른 회차 몫을 내리지 않는다.
 * 최대 대수를 넘으면 최대로 자른다.
 */
record ScalingFloors(int preorder, int waitingroom, boolean capped) {

    static ScalingFloors of(List<ScalingPlan> plans, ScalingProperties properties) {
        ScalingProperties.Preorder preorder = properties.preorder();
        ScalingProperties.Waitingroom waitingroom = properties.waitingroom();
        int preorderFloor = plans.isEmpty() ? preorder.baseline() : preorder.surge();
        // 예상 방문자 수를 넣지 않은 회차는 대기열을 평소대로 둔다 — 늘릴 근거가 없다
        int waitingroomFloor = plans.stream()
                .map(ScalingPlan::expectedVisitors)
                .filter(visitors -> visitors != null)
                .mapToInt(visitors -> Math.ceilDiv(visitors, waitingroom.visitorsPerTask()))
                .reduce(waitingroom.baseline(), Math::max);
        boolean capped = preorderFloor > preorder.max() || waitingroomFloor > waitingroom.max();
        return new ScalingFloors(Math.min(preorderFloor, preorder.max()), Math.min(waitingroomFloor, waitingroom.max()),
                capped);
    }
}
