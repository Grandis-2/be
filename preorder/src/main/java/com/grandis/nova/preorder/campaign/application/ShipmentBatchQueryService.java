package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.campaign.CachedCampaign;
import com.grandis.nova.preorder.campaign.ShipmentBatchSnapshot;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 배송 차수 공개 조회. 회차 캐시로만 답한다. 회차가 없거나 비공개거나 차수가 없으면(준비 전) 접수 현황과 같이 404 다 —
 * 비공개 상품은 있는지도 알리지 않는다.
 */
@Service
public class ShipmentBatchQueryService {

    private final CampaignCache cache;

    ShipmentBatchQueryService(CampaignCache cache) {
        this.cache = cache;
    }

    /** @throws BusinessException PRODUCT_NOT_FOUND */
    public List<ShipmentBatchSnapshot> findPublished(UUID productId) {
        return requirePublished(cache.find(productId)).batches();
    }

    /** 공개 조회에 내보일 회차 — 있고, 공개했고, 차수를 정했다. */
    static CachedCampaign requirePublished(Optional<CachedCampaign> campaign) {
        return campaign.filter(CachedCampaign::visible)
                .filter(found -> !found.batches().isEmpty())
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.PRODUCT_NOT_FOUND));
    }
}
