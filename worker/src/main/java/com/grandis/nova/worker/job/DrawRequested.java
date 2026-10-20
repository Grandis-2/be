package com.grandis.nova.worker.job;

/**
 * DRAW_REQUESTED 의 payload. 회차 id 는 봉투의 aggregateId 다.
 *
 * @param paidEntries 그 회차의 결제 완료 응모 수(추첨 대상). Mock 이 받은 응모 수와 같아야 추첨한다
 * @param winnerCount 당첨 인원. paidEntries 보다 작다(이하면 order 가 전원 당첨으로 처리해 보내지 않는다)
 */
public record DrawRequested(Integer paidEntries, Integer winnerCount) {
}
