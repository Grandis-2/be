package com.grandis.nova.catalog.edit;

import com.grandis.nova.catalog.product.SaleStatus;

/**
 * 판매 상태 전환 결과.
 *
 * @param campaignCancellationRequested 사전예약 오픈 뒤 판매 중지(회차 취소)로 접수했는가. 이미 취소된 상품에 다시 보내도 true(이벤트는 다시 적지 않는다)
 */
public record SaleStatusView(Long productId, SaleStatus status, boolean campaignCancellationRequested) {
}
