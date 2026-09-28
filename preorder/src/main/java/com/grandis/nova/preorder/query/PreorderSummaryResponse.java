package com.grandis.nova.preorder.query;

import com.grandis.nova.preorder.campaign.ShipmentBatchResponse;
import com.grandis.nova.preorder.preorder.Preorder;
import com.grandis.nova.preorder.preorder.PreorderStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 예약 목록 한 줄(openapi PreorderSummary). 상품명 · 옵션명 · 가격은 접수 시점 값이다.
 *
 * @param version 늦게 도착한 이전 응답을 버리는 데 쓴다(preorders.event_sequence)
 */
public record PreorderSummaryResponse(
        String preorderId,
        Long productId,
        String productTitle,
        Long optionId,
        String optionTitle,
        BigDecimal unitPrice,
        PreorderStatus status,
        long queuePosition,
        ShipmentBatchResponse shipmentBatch,
        Instant createdAt,
        Instant payableFrom,
        Instant paymentDueAt,
        long version
) {

    public static PreorderSummaryResponse from(PreorderView.Summary view) {
        Preorder preorder = view.preorder();
        return new PreorderSummaryResponse(preorder.getPreorderToken(), preorder.getProductId(),
                preorder.getProductTitleSnapshot(), preorder.getOptionId(), preorder.getOptionTitleSnapshot(),
                preorder.getUnitPriceSnapshot(), preorder.getStatus(), preorder.getQueuePosition(),
                ShipmentBatchResponse.from(view.shipmentBatch()), preorder.getCreatedAt(),
                preorder.getPayableFrom(), preorder.paymentDueAt(), preorder.getEventSequence());
    }
}
