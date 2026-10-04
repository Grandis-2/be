package com.grandis.nova.preorder.cancel;

import com.grandis.nova.preorder.campaign.Campaigns;
import com.grandis.nova.preorder.cancel.application.CancelStarter;
import com.grandis.nova.preorder.integration.catalog.CatalogReader;
import com.grandis.nova.preorder.preorder.CancelReason;
import com.grandis.nova.preorder.preorder.EventActor;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.preorder.Preorders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

/**
 * 회차 판매 중지. 회차를 잠가 지금 마감한 뒤, 진행 중 예약을 작은 트랜잭션 단위로 나눠 취소를 시작한다.
 * 중간에 죽어도 같은 이벤트를 다시 받으면 남은 예약만 이어서 처리한다(이미 취소 중이면 대상에서 빠진다).
 */
@Service
public class CampaignCancelService {

    static final int BATCH_SIZE = 100;

    private static final List<PreorderStatus> ACTIVE = List.of(PreorderStatus.PENDING_SYNC, PreorderStatus.PAYABLE);
    private static final String DEFAULT_REASON = "사전예약 회차 판매 중지";
    private static final int REASON_MAX_LENGTH = 500;

    private final Campaigns campaigns;
    private final Preorders preorders;
    private final CancelStarter cancelStarter;
    private final CatalogReader catalogReader;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public CampaignCancelService(Campaigns campaigns, Preorders preorders,
                                 CancelStarter cancelStarter, CatalogReader catalogReader,
                                 TransactionTemplate transactionTemplate, Clock clock) {
        this.campaigns = campaigns;
        this.preorders = preorders;
        this.cancelStarter = cancelStarter;
        this.catalogReader = catalogReader;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public void cancel(Long productId, String reason) {
        campaigns.closeNow(productId, clock.instant());
        catalogReader.evict(productId);
        String eventReason = eventReason(reason);
        boolean more = true;
        while (more) {
            more = Boolean.TRUE.equals(transactionTemplate.execute(status -> cancelBatch(productId, eventReason)));
        }
    }

    /** @return 한 묶음을 가득 채웠으면 true — 남은 예약이 더 있을 수 있다 */
    private boolean cancelBatch(Long productId, String reason) {
        List<PreorderSnapshot> batch = preorders.findByProduct(productId, ACTIVE, BATCH_SIZE);
        batch.forEach(preorder -> cancelStarter.start(preorder, EventActor.ADMIN, reason,
                CancelReason.CAMPAIGN_CANCELED));
        return batch.size() == BATCH_SIZE;
    }

    /** 관리자 전이라 사유가 필요하다. 비었으면 기본 문구, 길면 이력 칸 길이로 자른다. */
    private String eventReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return DEFAULT_REASON;
        }
        return reason.length() > REASON_MAX_LENGTH ? reason.substring(0, REASON_MAX_LENGTH) : reason;
    }
}
