package com.grandis.nova.order.draw.domain.model;

import java.time.Instant;

/**
 * 회차의 응모 · 추첨 진행. 관리자가 "마감됐는데 왜 추첨이 안 되나" 를 본다 — 결제 확인 중 응모가 남아 있으면 기다리는 중이다.
 *
 * @param paidEntries        결제 완료(추첨 대상)
 * @param authorizingEntries 결제 확인 중 — 0 이 돼야 추첨한다
 * @param awaitingEntries    결제 대기(응모만 하고 응모비를 내지 않음 — 추첨 대상 아님)
 * @param winners            당첨(0원 주문이 생긴 응모)
 * @param drawRequestedAt    Mock 에 추첨을 요청한 시각. 전원 당첨이면 비어 있다
 * @param settledAt          결과를 반영한 시각
 */
public record DrawProgress(long paidEntries, long authorizingEntries, long awaitingEntries, long winners, Instant drawRequestedAt,
                           Instant settledAt) {
}
