package com.grandis.nova.waitingroom.admin;

import java.time.Instant;
import java.util.List;

/**
 * 대기열 현황. 이 노드가 받은 판정 재료 기준이다(리더가 1초마다 발행). 재료가 없으면 products 는 비고 snapshotAgeSeconds 는 null.
 *
 * @param leaderNodeId 지금 리더. 없으면 null(선출 중)
 * @param gateways     리더가 센 살아 있는 노드 수. 0 이면 세지 못한 것이다
 * @param globalCredit 적용된 전역 속도(운영값 × brakeFactor). brakeFactor 1 은 브레이크가 걸리지 않은 것이다
 * @param leaveGraceSeconds 이 노드가 진입 · 조회에 쓰는 이탈 유예
 * @param longWaitThresholdSeconds "대기가 길다" 기준 — 최대 대기 시간 운영값이다. 줄이 입장 속도 × 이 값을 넘으면 새 진입이
 *                                 막힌다(429). 운영값이 없으면(제한 없음) null
 */
public record WaitingroomStatusView(String nodeId, String leaderNodeId, boolean snapshotStale, Long snapshotAgeSeconds,
                                    Integer gateways, Long globalCredit, Double brakeFactor, Long maxWaitSeconds,
                                    Long leaveGraceSeconds, Long longWaitThresholdSeconds, List<ProductStatus> products) {

    /**
     * @param estimatedWaitSeconds 지금 줄을 서면 기다릴 시간(초, 올림) — 대기 인원 ÷ 입장 속도, 사용자 대기 화면과 같은 식이다.
     *                             줄이 없으면 0, 접수 중이 아니거나 멈춰서(비공개 · 전체 입장 정지) 모르면 null
     */
    public record ProductStatus(String productId, String phase, String runtime, long waiting, long credit, Long cap,
                                Instant opensAt, Instant closesAt, Long estimatedWaitSeconds) {
    }
}
