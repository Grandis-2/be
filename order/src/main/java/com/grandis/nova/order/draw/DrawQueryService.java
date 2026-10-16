package com.grandis.nova.order.draw;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.draw.domain.model.DrawCampaign;
import com.grandis.nova.order.draw.domain.model.DrawPhase;
import com.grandis.nova.order.draw.domain.model.DrawProgress;
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

    /**
     * 그 단계의 회차만, 최신순. 단계는 now 로 가른다 — 응답의 단계 칸도 같은 now 로 만들어야 한 응답 안에서 필터와 칸이 어긋나지 않는다.
     * phase 가 null 이면 전부.
     */
    public OffsetPage<DrawCampaign> list(DrawPhase phase, Instant now, int page, int size) {
        if (phase == null) {
            return list(page, size);
        }
        return readTransaction.execute(status -> campaigns.findNewestFirst(phase, now, page, size));
    }

    /** @throws BusinessException 404 DRAW_NOT_FOUND */
    public DrawCampaign get(UUID drawId) {
        return readTransaction.execute(status -> campaigns.findById(drawId))
                .orElseThrow(() -> new BusinessException(OrderErrorCode.DRAW_NOT_FOUND));
    }

    /** 응모 · 추첨 진행(관리자). @throws BusinessException 404 DRAW_NOT_FOUND */
    public DrawProgress progress(UUID drawId) {
        return readTransaction.execute(status -> campaigns.findProgress(drawId))
                .orElseThrow(() -> new BusinessException(OrderErrorCode.DRAW_NOT_FOUND));
    }

    /** 그 상품이 아직 반영되지 않은 회차의 증정품인가 — catalog 의 상품 삭제가 묻는다(contracts/order-internal.md). */
    public boolean giftInProgress(UUID productId) {
        return Boolean.TRUE.equals(readTransaction.execute(status -> campaigns.existsUnsettledByProductId(productId)));
    }

    public Instant now() {
        return clock.instant();
    }
}
