package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.preorder.campaign.domain.CloseNoticeStore;
import com.grandis.nova.preorder.campaign.domain.CloseNoticeStore.Closed;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaignRepository;
import com.grandis.nova.preorder.preorder.Preorders;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** 마감 하나를 알린다 — 표시와 아웃박스 행을 한 트랜잭션으로. {@link CampaignClosingJob} 이 회차마다 부른다. */
@Component
class CloseNoticeNotifier {

    private final PreorderCampaignRepository campaigns;
    private final CloseNoticeStore notices;
    private final Preorders preorders;
    private final OutboxWriter outboxWriter;

    CloseNoticeNotifier(PreorderCampaignRepository campaigns, CloseNoticeStore notices, Preorders preorders,
                        OutboxWriter outboxWriter) {
        this.campaigns = campaigns;
        this.notices = notices;
        this.preorders = preorders;
        this.outboxWriter = outboxWriter;
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
        if (!notices.mark(closed.productId(), closed.closesAt(), now)) {
            return false;
        }
        outboxWriter.append(new PreorderCampaignClosed(closed.productId(), closed.closesAt(), campaign.issuedCount(),
                preorders.countActive(closed.productId())));
        return true;
    }
}
