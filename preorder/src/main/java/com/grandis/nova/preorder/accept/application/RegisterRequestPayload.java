package com.grandis.nova.preorder.accept.application;

import java.util.UUID;

/**
 * 외부 예약 Mock 등록 요청 본문(contracts/external-mock.openapi.yaml RegisterRequest).
 * 접수 때 preorder_sync_jobs.request_payload 에 고정한다 — 재시도마다 같은 내용을 보내야 외부가 중복으로 본다.
 * 외부 키(Idempotency-Key)는 preorder_token 이며 본문의 ourReservationId 와 같다.
 * customerRef · itemCode 는 회원 · 상품 id 의 UUID 문자열이다.
 */
record RegisterRequestPayload(
        String ourReservationId,
        String customerRef,
        String itemCode,
        String optionCode,
        int qty,
        String scope
) {

    public static RegisterRequestPayload of(String preorderToken, UUID customerId, UUID productId, String sku,
                                            String scope) {
        return new RegisterRequestPayload(preorderToken, customerId.toString(), productId.toString(), sku, 1, scope);
    }
}
