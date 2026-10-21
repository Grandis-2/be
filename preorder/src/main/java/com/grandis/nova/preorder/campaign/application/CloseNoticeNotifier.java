package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.preorder.campaign.domain.CloseNoticeStore;
import com.grandis.nova.preorder.campaign.domain.CloseNoticeStore.Closed;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaignRepository;
import com.grandis.nova.preorder.preorder.Preorders;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** 마감 하나를 알린다 — 표시와 아웃박스 행을 한 트랜잭션으로. {@link CampaignClosingJob} 이 회차마다, 다시 열기가 덮기 전에 부른다. */
@Component
class CloseNoticeNotifier {

    private final PreorderCampaignRepository campaigns;
    private final CloseNoticeStore notices;
    private final Preorders preorders;
    private final OutboxWriter outboxWriter;
    private final CloseNoticeProperties properties;

    CloseNoticeNotifier(PreorderCampaignRepository campaigns, CloseNoticeStore notices, Preorders preorders,
                        OutboxWriter outboxWriter, CloseNoticeProperties properties) {
        this.campaigns = campaigns;
        this.notices = notices;
        this.preorders = preorders;
        this.outboxWriter = outboxWriter;
        this.properties = properties;
    }

    /**
     * 회차 행을 공유 잠금으로 읽어 마감 직전에 커밋 중인 접수를 기다린 뒤 센다. 그사이 다시 열려 마감 시각이 바뀌었으면 보내지 않는다.
     *
     * @return 이번에 알렸으면 true. 다른 인스턴스가 먼저 알렸거나 마감이 바뀌었으면 false
     */
    @Transactional
    public boolean notifyClosed(Closed closed, Instant now) {
        PreorderCampaign campaign = campaigns.findForShare(closed.productId()).orElse(null);
        if (campaign == null || !campaign.getClosesAt().equals(closed.closesAt()) || campaign.getClosesAt().isAfter(now)) {
            return false;
        }
        return notifyLocked(campaign, now);
    }

    /**
     * 다시 열기 직전, 지금 마감을 아직 알리지 않았으면 알린다 — 주기 작업보다 먼저 다시 열면 마감 시각이 덮여 그 마감을 잃는다.
     * 다시 열기 트랜잭션이 회차 행을 FOR UPDATE 로 잡은 채 부른다. 꺼져 있거나 창을 지난 마감은 주기 작업처럼 보내지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyBeforeReopen(PreorderCampaign campaign, Instant now) {
        if (properties.enabled() && campaign.getClosesAt().isAfter(now.minus(properties.window()))) {
            notifyLocked(campaign, now);
        }
    }

    private boolean notifyLocked(PreorderCampaign campaign, Instant now) {
        if (!notices.mark(campaign.getProductId(), campaign.getClosesAt(), now)) {
            return false;
        }
        outboxWriter.append(new PreorderCampaignClosed(campaign.getProductId(), campaign.getClosesAt(),
                campaign.issuedCount(), preorders.countActive(campaign.getProductId())));
        return true;
    }
}
