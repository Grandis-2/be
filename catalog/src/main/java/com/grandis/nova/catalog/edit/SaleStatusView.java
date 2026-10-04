package com.grandis.nova.catalog.edit;

import com.grandis.nova.catalog.product.SaleStatus;

/**
 * 판매 상태 전환 결과.
 *
 * @param campaignCancellationRequested 사전예약 오픈 뒤 판매 중지(회차 취소)를 접수했는가. 그 경로가 생기기 전까지는 늘 false
 */
public record SaleStatusView(Long productId, SaleStatus status, boolean campaignCancellationRequested) {
}
