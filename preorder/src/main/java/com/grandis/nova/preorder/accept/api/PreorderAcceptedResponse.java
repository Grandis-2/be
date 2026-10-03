package com.grandis.nova.preorder.accept.api;

import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.campaign.ShipmentBatchResponse;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.preorder.PreorderStatus;

import java.time.Instant;

/** 접수 응답(openapi PreorderAccepted). 재전송이면 기존 예약의 현재 값이라 status 가 PENDING_SYNC 가 아닐 수 있다. */
record PreorderAcceptedResponse(
        String preorderId,
        PreorderStatus status,
        long queuePosition,
        ShipmentBatchResponse shipmentBatch,
        Instant createdAt,
        String statusUrl,
        boolean replayed
) {

    static final String STATUS_URL = "/api/v1/preorders/";

    public static PreorderAcceptedResponse from(AcceptResult result) {
        PreorderSnapshot preorder = result.preorder();
        return new PreorderAcceptedResponse(preorder.preorderToken(), preorder.status(),
                preorder.queuePosition(), ShipmentBatchResponse.from(result.shipmentBatch()),
                preorder.createdAt(), STATUS_URL + preorder.preorderToken(), result.replayed());
    }
}
