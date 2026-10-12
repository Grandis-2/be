package com.grandis.nova.preorder.event;

import com.grandis.nova.preorder.syncjob.NewSyncAttempt;

import java.util.UUID;

/**
 * worker 가 외부 등록 · 취소를 포기했다.
 *
 * @param reason  끝낸 이유. 외부가 거절한 오류 코드이거나 worker 가 정한 값(재시도 소진 등)이다
 * @param attempt 마지막 차례. 호출하지 않고 끝냈으면 null
 */
public record ExternalJobFailed(UUID syncJobId, String preorderId, String jobType, String reason,
                                NewSyncAttempt attempt) {
}
