package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.campaign.CachedCampaign;
import com.grandis.nova.preorder.campaign.ShipmentBatchSnapshot;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaignRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * 접수 현황의 재료. 일정 · 차수는 회차 캐시에서, 다음 순번만 DB 에서 읽는다(순번은 비우기 없이 바뀐다).
 * 회차 행을 잠그지 않으므로 접수와 서로 기다리지 않는다.
 */
@Component
class CampaignProgressReader {

    private final CampaignCache cache;
    private final PreorderCampaignRepository campaigns;
    private final Clock clock;

    CampaignProgressReader(CampaignCache cache, PreorderCampaignRepository campaigns, Clock clock) {
        this.cache = cache;
        this.campaigns = campaigns;
        this.clock = clock;
    }

    /** @throws BusinessException PRODUCT_NOT_FOUND — 회차가 없거나, 아직 공개하지 않았거나, 배송 차수를 정하지 않은 상품 */
    public CampaignProgressSource read(UUID productId) {
        CachedCampaign campaign = ShipmentBatchQueryService.requirePublished(cache.find(productId));
        // 다음 순번이 속한 차수. 접수가 그사이 순번을 가져가도 안내가 한 건 늦을 뿐이다
        ShipmentBatchSnapshot nextBatch = campaigns.findNextQueuePosition(productId)
                .flatMap(position -> campaign.batches().stream()
                        .filter(batch -> batch.covers(position))
                        .findFirst())
                .orElse(null);
        return new CampaignProgressSource(campaign.schedule(), nextBatch, clock.instant());
    }
}
