package com.grandis.nova.catalog.listing;

import com.grandis.nova.catalog.integration.PreorderCampaigns;
import com.grandis.nova.catalog.product.Product;
import com.grandis.nova.catalog.product.SaleMode;
import org.springframework.stereotype.Component;

/**
 * 판매 방식별 준비 — 등록 이벤트를 받은 서비스가 자기 표에 행을 만들었는가. 사전예약은 preorder 회차가 있다(회차 일정 API), 일반은 order 가 재고 행을
 * 만들었다고 알린 시각(products.stock_ready_at). 목록의 노출 조건과 같은 정의다.
 */
@Component
public class ProductReadiness {

    private final PreorderCampaigns campaigns;
    private final ProductListingQueryRepository listings;

    public ProductReadiness(PreorderCampaigns campaigns, ProductListingQueryRepository listings) {
        this.campaigns = campaigns;
        this.listings = listings;
    }

    /** @throws com.grandis.nova.common.BusinessException 사전예약인데 preorder 가 답하지 않으면 503 · 연동 오류면 500({@link PreorderCampaigns#find}) */
    public boolean isReady(Product product) {
        return product.getSaleMode() == SaleMode.PREORDER ? campaigns.find(product.getId()).isPresent() : listings.isStockReady(product.getId());
    }
}
