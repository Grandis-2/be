package com.grandis.nova.order.client.preorder;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * preorder 의 결제 가능 확인 응답(preorder PayabilityResponse 와 같은 모양). 주문은 여기의 상품 · 옵션 · 이름 · 단가를
 * 그대로 복사한다. 결제 가능 여부는 preorder 가 판정한다 — order 는 기한을 다시 계산하지 않는다(규칙을 두 곳에 두지 않는다).
 * 상태 · 사유 문자열은 preorder 소유라 enum 으로 옮기지 않는다 — 값이 늘어도 역직렬화가 깨지지 않게.
 *
 * @param preorderId         예약 공개 UUID
 * @param preorderInternalId preorders.id. orders.preorder_id 에 저장한다. 밖으로 내보내지 않는다
 * @param customerId         예약 회원. 주문 회원으로 저장한다(복합 FK)
 * @param payableFrom        PAYABLE 이 된 시각. 그 전이면 null
 * @param paymentDueAt       결제 기한(= payableFrom + 24시간). 그 전이면 null
 * @param reason             payable 이 false 인 까닭(NOT_YET_REGISTERED · DUE_PASSED · CANCELING · CANCELED). true 면 null
 */
public record PreorderPayability(
        String preorderId,
        Long preorderInternalId,
        Long customerId,
        Long productId,
        Long optionId,
        String productTitle,
        String optionTitle,
        BigDecimal unitPrice,
        String status,
        Instant payableFrom,
        Instant paymentDueAt,
        boolean payable,
        String reason
) {

    static final String DUE_PASSED = "DUE_PASSED";

    public boolean isOwnedBy(Long customerId) {
        return this.customerId.equals(customerId);
    }

    /** 결제 기한이 지나 결제할 수 없다. 그 밖의 사유(등록 전 · 취소 중 · 취소됨 · 모르는 값)는 모두 "결제할 수 없는 예약" 이다. */
    public boolean isDuePassed() {
        return !payable && DUE_PASSED.equals(reason);
    }
}
