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
import java.time.Duration;
import java.time.Instant;
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
    /** 마감 뒤 이만큼 지나도 반영하지 못하면 경보 — 시작 금지로 남은 결제 확인 중이 풀리는 시간(결제창 만료 35분)보다 넉넉하게. */
    static final Duration STALL_AFTER = Duration.ofHours(1);
    /** 마감 뒤 이만큼까지만 경보한다. */
    static final Duration ALARM_UNTIL = Duration.ofDays(1);

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

    /**
     * 마감 뒤 {@link #STALL_AFTER} 가 지나도 결과를 반영하지 못한 회차를 ERROR 로 알린다 — 추첨 요청이 DLQ 로 갔거나 worker 가 없으면 "요청됨 ·
     * 미반영" 에, 결제가 끝나지 않으면(payment 에스컬레이션) "결제 확인 중" 에 머문다. 저절로 풀리지 않을 수 있어 사람이 본다. 경보는 마감 뒤
     * {@link #ALARM_UNTIL} 까지만 낸다 — 그 뒤로는 관리자 진행 조회로 본다(같은 회차가 끝없이 경보를 채우지 않게).
     */
    @Scheduled(fixedDelayString = "${nova.draw.stall-check-interval:10m}", initialDelayString = "${nova.draw.stall-check-interval:10m}")
    void checkStalled() {
        Instant now = clock.instant();
        for (UUID campaignId : campaigns.findUnsettled(now.minus(ALARM_UNTIL), now.minus(STALL_AFTER), BATCH)) {
            campaigns.findProgress(campaignId).ifPresent(progress -> log.error(
                    "드로우 결과 반영 지연 — 마감 뒤 {} 넘게 반영 못 함 drawId={} drawRequestedAt={} authorizingEntries={} paidEntries={}",
                    STALL_AFTER, campaignId, progress.drawRequestedAt(), progress.authorizingEntries(), progress.paidEntries()));
        }
    }

    /** @return 이번에 처리한 회차 수 */
    int closeDue() {
        List<UUID> due = campaigns.findDrawable(clock.instant().minus(DrawSettlement.CLOSE_GRACE), BATCH);
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
                // 장부를 고칠 때까지 주기마다 다시 실패한다 — 옵션 · 수량은 예외에 있다
                log.error("재고 어긋남 — 드로우 남은 확보를 반납하지 못해 반영을 미룬다, 다음 주기에 다시 drawId={}", campaignId, e);
            } else {
                log.error("드로우 회차 처리 실패, 다음 주기에 다시 drawId={}", campaignId, e);
            }
        }
        return false;
    }
}
