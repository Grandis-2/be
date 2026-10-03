package com.grandis.nova.payment.vo;

import java.util.Objects;
import java.util.UUID;

/**
 * 결제사 요청의 멱등 키(Idempotency-Key 헤더). 거래 행을 만들 때 발급하고, 재전송은 그 행의 키로 한다 —
 * 토스는 같은 키면 첫 응답을 돌려주므로, 응답을 잃은 요청을 다시 보내도 두 번 청구 · 환불되지 않는다.
 *
 * 행의 키가 평생 같다고 가정하지 않는다. 첫 응답이 일시 오류일 때도 토스가 그 오류를 되돌려주는지 확인되지 않았고,
 * 그렇다면 일시 오류 뒤에는 새 키가 필요하다. 교체는 CAPTURE · REFUND 모두 <b>같은 행</b>에서, 리스를 쥔 채 조건부 UPDATE 로 한다
 * (NV-102 에서 확인 후 결정). CAPTURE 를 새 행으로 다시 하는 길은 없다 — 토스 승인은 결제창 인증 때의
 * orderId · paymentKey 짝으로만 되는데, 새 행은 새 결제사 주문 번호를 받고 옛 번호 재사용은 uq_payment_tx_provider_order 가 막는다.
 */
public record IdempotencyKey(String value) {

    /** payment_transactions.idempotency_key varchar(64). 토스 한도(300자)보다 좁다. */
    public static final int MAX_LENGTH = 64;

    public IdempotencyKey {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("멱등 키는 1~%d자다".formatted(MAX_LENGTH));
        }
    }

    public static IdempotencyKey issue() {
        return new IdempotencyKey(UUID.randomUUID().toString());
    }
}
