package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.campaign.ShipmentBatchSnapshot;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaignRepository;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatch;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatchRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** 접수 현황의 재료를 DB 에서 읽는다. 회차 행을 잠그지 않으므로 접수와 서로 기다리지 않는다. */
@Component
class CampaignProgressReader {

    private final PreorderCampaignRepository campaigns;
    private final ShipmentBatchRepository batches;
    private final Clock clock;

    CampaignProgressReader(PreorderCampaignRepository campaigns, ShipmentBatchRepository batches, Clock clock) {
        this.campaigns = campaigns;
        this.batches = batches;
        this.clock = clock;
    }

    /** @throws BusinessException PRODUCT_NOT_FOUND — 회차가 없거나 아직 공개하지 않은 상품 */
    @Transactional(readOnly = true)
    public CampaignProgressSource read(UUID productId) {
        PreorderCampaign campaign = campaigns.findById(productId)
                .filter(PreorderCampaign::isVisible)
                .orElseThrow(() -> new BusinessException(PreorderErrorCode.PRODUCT_NOT_FOUND));
        // 다음 순번이 속한 차수. 접수가 그사이 순번을 가져가도 안내가 한 건 늦을 뿐이다
        ShipmentBatchSnapshot nextBatch = batches.findCovering(productId, campaign.getNextQueuePosition())
                .map(ShipmentBatch::toSnapshot).orElse(null);
        return new CampaignProgressSource(campaign.toSchedule(), nextBatch, clock.instant());
    }
}
