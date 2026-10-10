package com.grandis.nova.order.draw;

import com.grandis.nova.order.MySqlLockFailures;
import com.grandis.nova.order.draw.domain.repository.DrawCampaignStore;
import com.grandis.nova.order.stock.domain.exception.StockReleaseMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * 추첨할 차례인 회차를 주기마다 처리한다(기본 30초, nova.draw.close-interval) — 전원 당첨이면 반영, 아니면 Mock 에 추첨 요청
 * ({@link DrawSettlement#close}). 인스턴스가 여럿이어도 회차 행 잠금과 요청 · 반영 표식으로 한 번이다.
 *
 * 한 회차의 실패(잠금 · 장부 어긋남)는 그 회차만 건너뛰고 다음 주기에 다시 고른다.
 */
@Component
public class DrawCloser {

    private static final Logger log = LoggerFactory.getLogger(DrawCloser.class);

    static final int BATCH = 100;

    private final DrawCampaignStore campaigns;
    private final DrawSettlement settlement;
    private final Clock clock;

    public DrawCloser(DrawCampaignStore campaigns, DrawSettlement settlement, Clock clock) {
        this.campaigns = campaigns;
        this.settlement = settlement;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${nova.draw.close-interval:30s}", initialDelayString = "${nova.draw.close-interval:30s}")
    void run() {
        int closed = closeDue();
        if (closed > 0) {
            log.info("드로우 회차 {}개 추첨 요청 · 전원 당첨 반영", closed);
        }
    }

    /** @return 이번에 처리한 회차 수 */
    int closeDue() {
        List<UUID> due = campaigns.findDrawable(clock.instant(), BATCH);
        int closed = 0;
        for (UUID campaignId : due) {
            if (close(campaignId)) {
                closed++;
            }
        }
        return closed;
    }

    private boolean close(UUID campaignId) {
        try {
            return settlement.close(campaignId);
        } catch (PessimisticLockingFailureException e) {
            log.warn("드로우 회차 처리 잠금 실패(교착={}), 다음 주기에 다시 drawId={}", MySqlLockFailures.isDeadlock(e), campaignId, e);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof StockReleaseMismatchException || e instanceof StockReleaseMismatchException) {
                log.error("재고 어긋남 — 드로우 남은 확보를 반납하지 못해 반영을 미룬다, 다음 주기에 다시 drawId={}", campaignId);
            } else {
                log.error("드로우 회차 처리 실패, 다음 주기에 다시 drawId={}", campaignId, e);
            }
        }
        return false;
    }
}
