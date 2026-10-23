package com.grandis.nova.preorder.scaling;

import com.grandis.nova.preorder.campaign.ScalingDemand;

import java.util.OptionalLong;

/**
 * 지금 맞출 최소 대수. 회차마다 따로 정하지 않고 걸린 회차 중 가장 큰 예상 방문자 수로 정한다 — 겹친 회차 하나가 끝나도 다른 회차 몫을
 * 내리지 않는다. 최대 대수를 넘으면 최대로 자른다. preorder 는 관리자 입장 속도를 받을 대수까지 올린다 — 대기열의 입장 속도가
 * 살아 있는 preorder 용량의 합에 묶여, 대수가 그대로면 관리자가 올려도 입장이 늘지 않는다.
 */
record ScalingFloors(int preorder, int waitingroom, boolean capped) {

    /**
     * @param admissionCredit 관리자가 정한 초당 입장 인원. 없으면 surge 만 본다
     * @param acceptPerSecond 태스크 하나의 초당 접수 수 — 대기열이 더하는 태스크당 용량과 같은 값
     */
    static ScalingFloors of(ScalingDemand demand, OptionalLong admissionCredit, int acceptPerSecond,
                            ScalingProperties properties) {
        ScalingProperties.Preorder preorder = properties.preorder();
        ScalingProperties.Waitingroom waitingroom = properties.waitingroom();
        // 회차가 없으면 관리자가 미리 올려 둬도 늘리지 않는다 — 쓰지 않는 태스크가 떠 있지 않게
        int preorderFloor = demand.campaigns() == 0 ? preorder.baseline()
                : (int) Math.min(Math.max(preorder.surge(),
                        Math.ceilDiv(admissionCredit.orElse(0), acceptPerSecond)), Integer.MAX_VALUE);
        // 예상 방문자 수를 넣은 회차가 없으면 대기열은 평소대로 둔다 — 늘릴 근거가 없다
        int waitingroomFloor = demand.maxExpectedVisitors() == null ? waitingroom.baseline()
                : Math.max(waitingroom.baseline(),
                        Math.ceilDiv(demand.maxExpectedVisitors(), waitingroom.visitorsPerTask()));
        boolean capped = preorderFloor > preorder.max() || waitingroomFloor > waitingroom.max();
        return new ScalingFloors(Math.min(preorderFloor, preorder.max()), Math.min(waitingroomFloor, waitingroom.max()),
                capped);
    }
}
