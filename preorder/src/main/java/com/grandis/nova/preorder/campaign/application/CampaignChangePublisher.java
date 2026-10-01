package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;
import com.grandis.nova.preorder.outbox.OutboxWriter;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** 회차의 지금 일정을 회차 변경 이벤트로 아웃박스에 적는다. 회차를 바꾼 트랜잭션 안에서 부른다. */
@Component
public class CampaignChangePublisher {

    private final OutboxWriter outboxWriter;
    private final Clock clock;

    CampaignChangePublisher(OutboxWriter outboxWriter, Clock clock) {
        this.outboxWriter = outboxWriter;
        this.clock = clock;
    }

    public void publish(PreorderCampaign campaign, CampaignChange change) {
        outboxWriter.append(new PreorderCampaignChanged(campaign.getProductId(), campaign.getScheduleVersion(),
                campaign.getOpensAt(), campaign.getClosesAt(), clock.instant(), change));
    }
}
