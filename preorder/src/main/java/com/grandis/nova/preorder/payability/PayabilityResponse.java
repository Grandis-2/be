package com.grandis.nova.preorder.payability;

import com.grandis.nova.preorder.preorder.PayabilityBlocker;
import com.grandis.nova.preorder.preorder.Preorder;
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
        Preorder preorder = payability.preorder();
        return new PayabilityResponse(preorder.getPreorderToken(), preorder.getId(), preorder.getCustomerId(),
                preorder.getProductId(), preorder.getOptionId(), preorder.getProductTitleSnapshot(),
                preorder.getOptionTitleSnapshot(), preorder.getUnitPriceSnapshot(), preorder.getStatus(),
                preorder.getPayableFrom(), preorder.paymentDueAt(), payability.payable(), payability.blocker());
    }
}
