package com.grandis.nova.order.client.preorder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * preorder 가 알려 준 예약. 주문은 여기의 상품 · 옵션 · 이름 · 단가를 그대로 복사한다.
 * 상태 문자열은 preorder 소유라 enum 으로 옮기지 않는다 — 상태가 늘어도 역직렬화가 깨지지 않게.
 *
 * @param id          preorders.id (내부 id). orders.preorder_id 에 저장한다. 밖으로 내보내지 않는다
 * @param preorderId  preorder_token (공개 토큰)
 * @param payableFrom PAYABLE 이 된 시각. 그 전이면 null
 */
public record PreorderSnapshot(
        Long id,
        String preorderId,
        Long customerId,
        Long productId,
        Long optionId,
        String productTitle,
        String optionTitle,
        BigDecimal unitPrice,
        String status,
        Instant payableFrom
) {

    /**
     * 결제 기한. preorder {@code Preorder.PAYMENT_WINDOW} 와 같은 규칙이다(연장 없음).
     * 계약에 paymentDueAt 이 들어오면 그 값을 쓰고 이 상수는 지운다(계획서 §6-2).
     */
    static final Duration PAYMENT_WINDOW = Duration.ofHours(24);

    static final String PAYABLE = "PAYABLE";

    public boolean isOwnedBy(Long customerId) {
        return this.customerId.equals(customerId);
    }

    public boolean isPayable() {
        return PAYABLE.equals(status) && payableFrom != null;
    }

    /** 기한 시각이 되면 이미 지난 것으로 본다. PAYABLE 이 아니면 쓰지 않는다. */
    public boolean isPaymentWindowOver(Instant now) {
        return !now.isBefore(payableFrom.plus(PAYMENT_WINDOW));
    }
}
