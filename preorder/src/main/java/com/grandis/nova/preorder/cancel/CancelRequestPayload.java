package com.grandis.nova.preorder.cancel;

/**
 * 외부 예약 Mock 취소 요청 본문(contracts/external-mock.openapi.yaml CancelRequest).
 * CANCEL 작업을 만들 때 preorder_sync_jobs.request_payload 에 고정한다.
 *
 * @param externalKey   등록 때 쓴 외부 키(preorder_token)
 * @param reservationNo 외부 예약 번호. 등록이 확인되지 않았으면 null
 * @param reason        감사용 사유(USER_CANCEL · ADMIN_CANCEL · DEADLINE_EXCEEDED)
 */
public record CancelRequestPayload(String externalKey, String reservationNo, String reason) {
}
