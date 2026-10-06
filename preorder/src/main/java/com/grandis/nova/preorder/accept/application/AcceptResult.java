package com.grandis.nova.preorder.accept.application;

import com.grandis.nova.preorder.campaign.ShipmentBatchSnapshot;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;

/**
 * 접수 결과.
 *
 * @param replayed 같은 접수 키의 기존 예약을 돌려줬으면 true. 새 순번을 쓰지 않았다
 */
public record AcceptResult(PreorderSnapshot preorder, ShipmentBatchSnapshot shipmentBatch, boolean replayed) {
}
