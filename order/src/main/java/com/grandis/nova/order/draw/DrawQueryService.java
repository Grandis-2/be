package com.grandis.nova.order.draw;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.draw.domain.model.DrawCampaign;
import com.grandis.nova.order.draw.domain.repository.DrawCampaignStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** 회차 목록 · 상세. 관리자 화면과 회원 화면이 같이 쓴다 — 단계(예정 · 응모 중 · 마감)는 {@link #now()} 로 가른다. */
@Service
public class DrawQueryService {

    private final DrawCampaignStore campaigns;
    private final TransactionTemplate readTransaction;
    private final Clock clock;

    public DrawQueryService(DrawCampaignStore campaigns, PlatformTransactionManager transactionManager, Clock clock) {
        this.campaigns = campaigns;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.clock = clock;
    }

    /** 최신순. page 는 0 부터. */
    public OffsetPage<DrawCampaign> list(int page, int size) {
        return readTransaction.execute(status -> campaigns.findNewestFirst(page, size));
    }

    /** @throws BusinessException 404 DRAW_NOT_FOUND */
    public DrawCampaign get(UUID drawId) {
        return readTransaction.execute(status -> campaigns.findById(drawId))
                .orElseThrow(() -> new BusinessException(OrderErrorCode.DRAW_NOT_FOUND));
    }

    public Instant now() {
        return clock.instant();
    }
}
