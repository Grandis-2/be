package com.grandis.nova.waitingroom.admin;

import java.time.Instant;
import java.util.List;

/**
 * 대기열 현황. 이 노드가 받은 판정 재료 기준이다(리더가 1초마다 발행). 재료가 없으면 products 는 비고 snapshotAgeSeconds 는 null.
 *
 * @param leaderNodeId 지금 리더. 없으면 null(선출 중)
 * @param gateways     리더가 센 살아 있는 노드 수. 0 이면 세지 못한 것이다
 * @param globalCredit 적용된 전역 속도(운영값 × brakeFactor). brakeFactor 1 은 브레이크가 걸리지 않은 것이다
 */
public record WaitingroomStatusView(String nodeId, String leaderNodeId, boolean snapshotStale, Long snapshotAgeSeconds,
                                    Integer gateways, Long globalCredit, Double brakeFactor, Long maxWaitSeconds,
                                    List<ProductStatus> products) {

    public record ProductStatus(String productId, String phase, String runtime, long waiting, long credit, Long cap,
                                Instant opensAt, Instant closesAt) {
    }
}
