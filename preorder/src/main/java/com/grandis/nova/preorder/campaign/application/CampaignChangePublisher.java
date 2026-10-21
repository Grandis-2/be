package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * 회차의 지금 일정을 회차 변경 이벤트로 아웃박스에 적고(대기열 · 켜 두면 catalog), 커밋 뒤 회차 캐시를 비운다.
 * 회차를 바꾼 트랜잭션 안에서 부른다.
 * 전체 재발행(RESYNC)은 회차를 바꾸지 않으므로 비우지 않는다.
 */
@Component
public class CampaignChangePublisher {

    private final OutboxWriter outboxWriter;
    private final CampaignCacheInvalidation cacheInvalidation;
    private final ScheduleNoticeProperties scheduleNotice;
    private final Clock clock;

    CampaignChangePublisher(OutboxWriter outboxWriter, CampaignCacheInvalidation cacheInvalidation,
                            ScheduleNoticeProperties scheduleNotice, Clock clock) {
        this.outboxWriter = outboxWriter;
        this.cacheInvalidation = cacheInvalidation;
        this.scheduleNotice = scheduleNotice;
        this.clock = clock;
    }

    public void publish(PreorderCampaign campaign, CampaignChange change) {
        Instant now = clock.instant();
        outboxWriter.append(new PreorderCampaignChanged(campaign.getProductId(), campaign.getScheduleVersion(),
                campaign.getOpensAt(), campaign.getClosesAt(), campaign.isVisible(), now, change));
        if (scheduleNotice.enabled()) {
            outboxWriter.append(new PreorderCampaignScheduleChanged(campaign.getProductId(),
                    campaign.getScheduleVersion(), campaign.getOpensAt(), campaign.getClosesAt(), now, change));
        }
        if (change != CampaignChange.RESYNC) {
            cacheInvalidation.evictAfterCommit(campaign.getProductId());
        }
    }

    /**
     * 상품 사본(이름 · 판매 상태 · 공개 여부)을 반영했다. 판매 상태도 캐시에 있으므로 늘 비우고, 회차 변경 이벤트는 공개 여부가
     * 바뀌었을 때만 적는다(대기열은 상품명 · 판매 상태를 보지 않는다).
     */
    public void listingApplied(PreorderCampaign campaign, boolean visibilityChanged) {
        if (visibilityChanged) {
            publish(campaign, CampaignChange.VISIBILITY);
        } else {
            cacheInvalidation.evictAfterCommit(campaign.getProductId());
        }
    }

    /** 배송 차수를 바꿨다 — 일정은 그대로라 이벤트 없이 캐시만 비운다. */
    void batchesReplaced(UUID productId) {
        cacheInvalidation.evictAfterCommit(productId);
    }
}
