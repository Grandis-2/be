package com.grandis.nova.preorder.payability;

import com.grandis.nova.preorder.preorder.PayabilityBlocker;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.preorder.PreorderStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** @param preorderInternalId order 가 주문의 예약 FK 로 저장할 내부 id */
record PayabilityResponse(
        String preorderId,
        Long preorderInternalId,
        Long customerId,
        Long productId,
        Long optionId,
        String productTitle,
        String optionTitle,
        BigDecimal unitPrice,
        PreorderStatus status,
        Instant payableFrom,
        Instant paymentDueAt,
        boolean payable,
        PayabilityBlocker reason
) {

    public static PayabilityResponse from(Payability payability) {
        PreorderSnapshot preorder = payability.preorder();
        return new PayabilityResponse(preorder.preorderToken(), preorder.id(), preorder.customerId(),
                preorder.productId(), preorder.optionId(), preorder.productTitle(),
                preorder.optionTitle(), preorder.unitPrice(), preorder.status(),
                preorder.payableFrom(), preorder.paymentDueAt(), payability.payable(), payability.blocker());
    }
}
