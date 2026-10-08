package com.grandis.nova.preorder.campaign;

import com.grandis.nova.preorder.campaign.application.CampaignChange;
import com.grandis.nova.preorder.campaign.application.CampaignChangePublisher;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaignRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 전체 재발행(모듈 공개 API). 대기열이 일정을 잃었을 때 마감 전 회차마다 지금 일정을 회차 변경 이벤트로 다시 보낸다.
 * 일정 번호는 올리지 않는다 — 늦게 도착해도 그 사이의 실제 변경을 덮지 못하고, 여러 번 불러도 결과가 같다.
 */
@Service
public class CampaignRepublisher {

    private static final int BATCH_SIZE = 100;

    /** 첫 묶음의 커서. 모든 상품 id 보다 작다. */
    private static final UUID BEFORE_FIRST = new UUID(0, 0);

    private static final Logger log = LoggerFactory.getLogger(CampaignRepublisher.class);

    private final PreorderCampaignRepository campaigns;
    private final CampaignChangePublisher changePublisher;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    CampaignRepublisher(PreorderCampaignRepository campaigns, CampaignChangePublisher changePublisher,
                        TransactionTemplate transactionTemplate, Clock clock) {
        this.campaigns = campaigns;
        this.changePublisher = changePublisher;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /** 묶음마다 짧은 트랜잭션으로 적는다. 각 이벤트는 그 회차를 읽은 시점의 일정 · 번호다. @return 적은 이벤트 수 */
    public int republishAll() {
        Instant now = clock.instant();
        int published = 0;
        UUID afterProductId = BEFORE_FIRST;
        List<UUID> batch;
        do {
            UUID after = afterProductId;
            batch = transactionTemplate.execute(status -> republishBatch(now, after));
            published += batch.size();
            if (!batch.isEmpty()) {
                afterProductId = batch.getLast();
            }
        } while (batch.size() == BATCH_SIZE);
        log.info("회차 일정 전체 재발행: {}건", published);
        return published;
    }

    private List<UUID> republishBatch(Instant now, UUID afterProductId) {
        List<PreorderCampaign> batch = campaigns.findNotClosedAfter(now, afterProductId, Limit.of(BATCH_SIZE));
        batch.forEach(campaign -> changePublisher.publish(campaign, CampaignChange.RESYNC));
        return batch.stream().map(PreorderCampaign::getProductId).toList();
    }
}
