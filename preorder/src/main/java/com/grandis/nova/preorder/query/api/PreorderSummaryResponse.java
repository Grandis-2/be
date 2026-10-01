package com.grandis.nova.preorder.query.api;

import com.grandis.nova.preorder.campaign.ShipmentBatchResponse;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.query.application.PreorderView;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 예약 목록 한 줄(openapi PreorderSummary). 상품명 · 옵션명 · 가격은 접수 시점 값이다.
 *
 * @param version 늦게 도착한 이전 응답을 버리는 데 쓴다(preorders.event_sequence)
 */
record PreorderSummaryResponse(
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
        PreorderSnapshot preorder = view.preorder();
        return new PreorderSummaryResponse(preorder.preorderToken(), preorder.productId(),
                preorder.productTitle(), preorder.optionId(), preorder.optionTitle(),
                preorder.unitPrice(), preorder.status(), preorder.queuePosition(),
                ShipmentBatchResponse.from(view.shipmentBatch()), preorder.createdAt(),
                preorder.payableFrom(), preorder.paymentDueAt(), preorder.eventSequence());
    }
}
