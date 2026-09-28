package com.grandis.nova.preorder.accept;

import com.grandis.nova.preorder.campaign.ShipmentBatchResponse;
import com.grandis.nova.preorder.preorder.Preorder;
import com.grandis.nova.preorder.preorder.PreorderStatus;

import java.time.Instant;

/** 접수 응답(openapi PreorderAccepted). 재전송이면 기존 예약의 현재 값이라 status 가 PENDING_SYNC 가 아닐 수 있다. */
public record PreorderAcceptedResponse(
        String preorderId,
        PreorderStatus status,
        long queuePosition,
        ShipmentBatchResponse shipmentBatch,
        Instant createdAt,
        String statusUrl,
        boolean replayed
) {

    static final String STATUS_URL = "/api/v1/preorders/";

    static PreorderAcceptedResponse from(AcceptResult result) {
        Preorder preorder = result.preorder();
        return new PreorderAcceptedResponse(preorder.getPreorderToken(), preorder.getStatus(),
                preorder.getQueuePosition(), ShipmentBatchResponse.from(result.shipmentBatch()),
                preorder.getCreatedAt(), STATUS_URL + preorder.getPreorderToken(), result.replayed());
    }
}
