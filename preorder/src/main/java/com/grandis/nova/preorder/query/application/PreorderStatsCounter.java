package com.grandis.nova.preorder.query.application;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.campaign.CampaignPhase;
import com.grandis.nova.preorder.campaign.CampaignSchedule;
import com.grandis.nova.preorder.campaign.Campaigns;
import com.grandis.nova.preorder.deadletter.DeadLetterBacklog;
import com.grandis.nova.preorder.deadletter.DeadLetterWaiting;
import com.grandis.nova.preorder.preorder.PreorderBacklog;
import com.grandis.nova.preorder.preorder.Preorders;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;
import com.grandis.nova.preorder.syncjob.SyncJobType;
import com.grandis.nova.preorder.syncjob.SyncJobs;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 운영 현황을 실제로 센다. 읽기 전용 트랜잭션 하나로 묶어 숫자들이 같은 시점의 것이 되게 한다. */
@Component
class PreorderStatsCounter {

    private final Preorders preorders;
    private final SyncJobs syncJobs;
    private final DeadLetterBacklog deadLetters;
    private final Campaigns campaigns;
    private final Clock clock;

    PreorderStatsCounter(Preorders preorders, SyncJobs syncJobs, DeadLetterBacklog deadLetters, Campaigns campaigns,
                         Clock clock) {
        this.preorders = preorders;
        this.syncJobs = syncJobs;
        this.deadLetters = deadLetters;
        this.campaigns = campaigns;
        this.clock = clock;
    }

    /** @throws BusinessException PRODUCT_NOT_FOUND — 사전예약 회차가 없는 상품 */
    @Transactional(readOnly = true)
    public PreorderStats count(UUID productId) {
        if (productId != null && !campaigns.exists(productId)) {
            throw new BusinessException(PreorderErrorCode.PRODUCT_NOT_FOUND);
        }
        Instant now = clock.instant();
        return new PreorderStats(productId, preorders.backlog(productId, now), syncJobs.countUnfinished(productId),
                deadLetters.waiting(productId), now);
    }

    /** 그 단계 회차 한 페이지와 그 상품들의 현황. 상품마다 따로 묻지 않고 숫자별로 문장 하나씩 묶어 센다. */
    @Transactional(readOnly = true)
    public ProductStatsPage countProducts(CampaignPhase phase, PhaseCursor cursor, int size) {
        Instant now = clock.instant();
        List<CampaignSchedule> found = new ArrayList<>(campaigns.findPhasePage(phase, now, cursor.closesAt(),
                cursor.productId(), size + 1));
        String nextCursor = null;
        if (found.size() > size) {
            found.subList(size, found.size()).clear();
            nextCursor = PhaseCursor.after(found.getLast());
        }
        List<UUID> productIds = found.stream().map(CampaignSchedule::productId).toList();
        Map<UUID, PreorderBacklog> backlogs = preorders.backlogs(productIds, now);
        Map<UUID, Map<SyncJobType, Map<SyncJobStatus, Long>>> jobs = syncJobs.countUnfinished(productIds);
        Map<UUID, DeadLetterWaiting> waiting = deadLetters.waiting(productIds);
        List<ProductStats> items = found.stream().map(campaign -> new ProductStats(campaign,
                backlogs.get(campaign.productId()), jobs.get(campaign.productId()),
                waiting.get(campaign.productId()))).toList();
        return new ProductStatsPage(items, nextCursor, now);
    }
}
