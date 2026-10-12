package com.grandis.nova.preorder.event;

import com.grandis.nova.preorder.syncjob.NewSyncAttempt;

import java.util.UUID;

/**
 * worker 가 외부 등록 · 취소에 성공했다. 작업 종류와 예약은 payload 를 믿지 않고 작업 원장에서 읽는다.
 * 원장에 없어 payload 에서 받는 값은 externalNumber 와 차례 기록(attempt)이다.
 *
 * @param externalNumber 외부가 준 예약 번호. 취소는 null 일 수 있다
 */
public record ExternalJobSucceeded(UUID syncJobId, String preorderId, String jobType, String externalNumber,
                                   NewSyncAttempt attempt) {
}
