package com.grandis.nova.preorder.scaling;

import com.grandis.nova.preorder.campaign.ScalingDemand;

/**
 * 지금 맞출 최소 대수. 회차마다 따로 정하지 않고 걸린 회차 중 가장 큰 예상 방문자 수로 정한다 — 겹친 회차 하나가 끝나도 다른 회차 몫을
 * 내리지 않는다. 최대 대수를 넘으면 최대로 자른다.
 */
record ScalingFloors(int preorder, int waitingroom, boolean capped) {

    static ScalingFloors of(ScalingDemand demand, ScalingProperties properties) {
        ScalingProperties.Preorder preorder = properties.preorder();
        ScalingProperties.Waitingroom waitingroom = properties.waitingroom();
        int preorderFloor = demand.campaigns() == 0 ? preorder.baseline() : preorder.surge();
        // 예상 방문자 수를 넣은 회차가 없으면 대기열은 평소대로 둔다 — 늘릴 근거가 없다
        int waitingroomFloor = demand.maxExpectedVisitors() == null ? waitingroom.baseline()
                : Math.max(waitingroom.baseline(),
                        Math.ceilDiv(demand.maxExpectedVisitors(), waitingroom.visitorsPerTask()));
        boolean capped = preorderFloor > preorder.max() || waitingroomFloor > waitingroom.max();
        return new ScalingFloors(Math.min(preorderFloor, preorder.max()), Math.min(waitingroomFloor, waitingroom.max()),
                capped);
    }
}
