package com.grandis.nova.payment.client.toss;

/**
 * 토스 POST(승인 · 취소)에 싣는 멱등 키. 같은 키 + API 키 + 주소 + 메서드면 토스는 첫 요청의 응답을 돌려준다(15일 유효).
 * POST 메서드는 이 타입만 받는다 — 키 없는 승인 · 취소가 컴파일되지 않게. 키를 만들고 저장하는 것은 결제 시도(NV-99)다.
 *
 * @param value 최대 300자(넘으면 토스 400 INVALID_IDEMPOTENCY_KEY). 시크릿이 아니다
 */
public record TossIdempotencyKey(String value) {

    public static final String HEADER = "Idempotency-Key";
    static final int MAX_LENGTH = 300;

    public TossIdempotencyKey {
        if (value == null || value.isBlank() || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("멱등 키는 비어 있지 않은 " + MAX_LENGTH + "자 이하여야 한다");
        }
    }
}
