package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.preorder.campaign.ShipmentBatchSnapshot;
import com.grandis.nova.preorder.campaign.domain.PreorderSaleStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * 상품의 접수 현황.
 *
 * @param nextBatch 지금 신청하면 배정될 배송 차수. 접수 중이 아니면 null
 * @param countedAt 일정 · 차수를 DB 에서 읽은 시각. 캐시된 값이면 지금보다 이전이다
 */
public record CampaignProgress(UUID productId, PreorderSaleStatus saleStatus, ShipmentBatchSnapshot nextBatch,
                               Instant countedAt) {
}
