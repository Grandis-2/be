package com.grandis.nova.payment.client.toss;

/**
 * 결제 조회 결과. 조회는 아무것도 처리하지 않으므로 승인 · 취소와 분류가 다르다 — 찾음 / 없음 / 알 수 없음.
 * 찾은 결제의 상태를 무엇으로 볼지는 부르는 쪽(복구 NV-102 · 환불 확인 NV-103)이 정한다.
 */
public sealed interface TossLookupResult {

    record Found(TossPayment payment) implements TossLookupResult {
    }

    /**
     * 토스에 그 결제가 없다(404). 뜻은 어느 조회냐에 따라 다르다.
     * - paymentKey 조회: 그 결제 키의 결제가 토스에 없다.
     * - orderId 조회: **승인된** 결제가 없다(토스 문서). 처리됐지만 실패한 결제(ABORTED · EXPIRED)도 여기로 온다 — 없음 ≠ 실패.
     * 승인 · 취소의 결과 불명 복구는 paymentKey 조회를 쓴다({@link UnknownReason.Resolution#LOOKUP}).
     */
    record NotFound(String code) implements TossLookupResult {
    }

    /** 알 수 없다 — 나중에 다시 조회한다. */
    record Unknown(UnknownReason reason, String code) implements TossLookupResult {
    }
}
