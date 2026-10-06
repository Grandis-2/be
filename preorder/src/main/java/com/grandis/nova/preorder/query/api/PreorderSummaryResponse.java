package com.grandis.nova.preorder.query.api;

import com.grandis.nova.preorder.campaign.ShipmentBatchResponse;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.query.application.PreorderDisplayStatus;
import com.grandis.nova.preorder.query.application.PreorderView;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 예약 목록 한 줄(openapi PreorderSummary). 상품명 · 옵션명 · 가격은 접수 시점 값이다.
 *
 * @param displayStatus 화면 단계(문구 · 버튼은 이 값으로). status 는 원장 상태다
 * @param reservedAt    예약 확정(결제 확인) 시각. 확정 전이면 null
 * @param version       늦게 도착한 이전 응답을 버리는 데 쓴다(preorders.event_sequence)
 */
record PreorderSummaryResponse(
        String preorderId,
        Long productId,
        String productTitle,
        Long optionId,
        String optionTitle,
        BigDecimal unitPrice,
        PreorderStatus status,
        PreorderDisplayStatus displayStatus,
        long queuePosition,
        ShipmentBatchResponse shipmentBatch,
        Instant createdAt,
        Instant payableFrom,
        Instant paymentDueAt,
        Instant reservedAt,
        long version
) {

    public static PreorderSummaryResponse from(PreorderView.Summary view) {
        PreorderSnapshot preorder = view.preorder();
        return new PreorderSummaryResponse(preorder.preorderToken(), preorder.productId(),
                preorder.productTitle(), preorder.optionId(), preorder.optionTitle(),
                preorder.unitPrice(), preorder.status(), view.displayStatus(), preorder.queuePosition(),
                ShipmentBatchResponse.from(view.shipmentBatch()), preorder.createdAt(),
                preorder.payableFrom(), preorder.paymentDueAt(), preorder.reservedAt(), preorder.eventSequence());
    }
}
