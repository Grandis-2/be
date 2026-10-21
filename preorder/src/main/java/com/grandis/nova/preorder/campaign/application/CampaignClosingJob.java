package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.preorder.campaign.domain.CloseNoticeStore;
import com.grandis.nova.preorder.campaign.domain.CloseNoticeStore.Closed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * 회차가 닫히면 catalog 에 알린다({@link PreorderCampaignClosed}). 마감은 시각이 지나는 것이라 주기적으로 찾는다 — 조기 마감 · 판매 중지도
 * 마감 시각을 지금으로 바꾸므로 같은 길이다. 인스턴스마다 돌지만 표시 행의 키로 마감마다 한 번이다. 회차 하나의 실패는 로그만 남기고
 * 다음 회차로 간다(롤백돼 창 안에서 다음 주기에 다시 집는다).
 */
@Component
class CampaignClosingJob {

    private static final Logger log = LoggerFactory.getLogger(CampaignClosingJob.class);

    private final CloseNoticeStore notices;
    private final CloseNoticeNotifier notifier;
    private final CloseNoticeProperties properties;
    private final Clock clock;

    /** 지난 주기의 창 시작. 그 뒤로 알리지 못한 채 창을 벗어난 마감을 한 번만 로그로 남긴다(인스턴스마다 한 번). */
    private Instant lastSince;

    CampaignClosingJob(CloseNoticeStore notices, CloseNoticeNotifier notifier, CloseNoticeProperties properties,
                       Clock clock) {
        this.notices = notices;
        this.notifier = notifier;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${nova.campaign.close-notice.interval:30s}",
            initialDelayString = "${nova.campaign.close-notice.interval:30s}")
    void run() {
        if (properties.enabled()) {
            notifyClosedCampaigns();
        }
    }

    /** @return 알린 마감 수 */
    int notifyClosedCampaigns() {
        Instant now = clock.instant();
        Instant since = now.minus(properties.window());
        reportExpired(since);
        int notified = 0;
        for (Closed closed : notices.findPending(since, now, properties.batchSize())) {
            try {
                if (notifier.notifyClosed(closed, now)) {
                    notified++;
                }
            } catch (RuntimeException e) {
                log.error("마감 알림 실패 — 다음 주기에 다시 한다 productId={}", closed.productId(), e);
            }
        }
        return notified;
    }

    private void reportExpired(Instant since) {
        Instant after = lastSince;
        lastSince = since;
        if (after == null || !since.isAfter(after)) {
            return;
        }
        for (Closed closed : notices.findPending(after, since, properties.batchSize())) {
            log.warn("마감 알림을 보내지 못한 채 창이 지났다 productId={} closesAt={}", closed.productId(), closed.closesAt());
        }
    }
}
