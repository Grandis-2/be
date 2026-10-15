package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.preorder.campaign.ProductStatus;
import com.grandis.nova.preorder.campaign.application.OpenAlertNotifier.Chunk;
import com.grandis.nova.preorder.campaign.domain.OpenAlertDispatchStore;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaignRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * 회차가 열리면 오픈 알림 신청자에게 알린다. 오픈은 누가 누르는 동작이 아니라 시각이 지나는 것이라 주기적으로 찾는다.
 * 주기마다 창 안의 회차를 표시하고, 끝나지 않은 발송(이번에 표시한 것 · 앞 주기에 죽은 것)을 묶음씩 이어 보낸다({@link OpenAlertNotifier}).
 * 인스턴스마다 돌지만 표시는 조건부 UPDATE 로, 묶음은 진행 행 잠금으로 한 번씩이다. 회차 하나의 실패는 로그만 남기고 다음 회차로 간다 —
 * 표시 실패는 롤백돼 다음 주기에 다시 집고(창 안에서), 묶음 실패는 커서가 그대로라 다음 주기에 이어 간다(그사이 마감 · 창이 지나면 그만둔다).
 */
@Component
class CampaignOpeningJob {

    static final String SKIPPED_METRIC = "preorder.open_alert.skipped";

    private static final Logger log = LoggerFactory.getLogger(CampaignOpeningJob.class);

    private final PreorderCampaignRepository campaigns;
    private final OpenAlertDispatchStore dispatches;
    private final OpenAlertNotifier notifier;
    private final OpenAlertProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /** 지난 주기의 창 시작. 그 뒤로 창을 벗어난 회차를 한 번만 로그로 남긴다(인스턴스마다 한 번). */
    private Instant lastSince;

    CampaignOpeningJob(PreorderCampaignRepository campaigns, OpenAlertDispatchStore dispatches,
                       OpenAlertNotifier notifier, OpenAlertProperties properties, MeterRegistry meterRegistry,
                       Clock clock) {
        this.campaigns = campaigns;
        this.dispatches = dispatches;
        this.notifier = notifier;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${nova.campaign.open-alert.interval:30s}",
            initialDelayString = "${nova.campaign.open-alert.interval:30s}")
    void run() {
        notifyOpenedCampaigns();
    }

    /** @return 표시한 회차 수 */
    int notifyOpenedCampaigns() {
        Instant now = clock.instant();
        Instant since = now.minus(properties.window());
        int marked = markOpened(now, since);
        countSkipped(now, since);
        reportExpired(now, since);
        dispatchPending();
        return marked;
    }

    /** 창 안의 보낼 회차를 표시한다. 비공개 · 판매 중지는 조회에서 빠진다 — 창 안에 다시 공개 · 판매되면 그때 집는다. */
    private int markOpened(Instant now, Instant since) {
        int marked = 0;
        for (PreorderCampaign campaign : campaigns.findOpenAlertWindow(now, since, ProductStatus.PAUSED,
                Limit.of(properties.batchSize()))) {
            UUID productId = campaign.getProductId();
            try {
                if (notifier.markOpened(productId, now, since)) {
                    marked++;
                }
            } catch (RuntimeException e) {
                log.error("오픈 알림 표시 실패 — 다음 주기에 다시 한다 productId={}", productId, e);
            }
        }
        return marked;
    }

    /** 창 안에서 건너뛰는 회차를 이유별로 센다. 표시 대상 조회와 따로 세 상한에 걸리지 않는다. */
    private void countSkipped(Instant now, Instant since) {
        long hidden = campaigns.countOpenAlertWindowHidden(now, since);
        long paused = campaigns.countOpenAlertWindowPaused(now, since, ProductStatus.PAUSED);
        meterRegistry.counter(SKIPPED_METRIC, "reason", SkipReason.HIDDEN.name()).increment(hidden);
        meterRegistry.counter(SKIPPED_METRIC, "reason", SkipReason.PAUSED.name()).increment(paused);
    }

    /** 보내지 못한 채 창을 벗어난 회차를 이유와 함께 남긴다. 주기마다 지난 주기 뒤로 벗어난 것만 봐 회차당 한 번이다. */
    private void reportExpired(Instant now, Instant since) {
        Instant after = lastSince;
        lastSince = since;
        if (after == null || !since.isAfter(after)) {
            return;
        }
        for (PreorderCampaign campaign : campaigns.findOpenAlertExpired(after, since)) {
            SkipReason skipped = SkipReason.of(campaign);
            if (skipped != null) {
                log.info("오픈 알림을 보내지 않고 창이 지났다 productId={} reason={}", campaign.getProductId(), skipped);
            } else if (campaign.getClosesAt().isAfter(now)) {
                log.warn("오픈 알림을 표시하지 못한 채 창이 지났다(표시 실패 · 주기 상한 초과) productId={}", campaign.getProductId());
            }
        }
    }

    /** 끝나지 않은 발송을 묶음씩 끝까지 보낸다. 다른 인스턴스가 이미 끝낸 발송은 남기지 않는다. */
    private void dispatchPending() {
        for (UUID productId : dispatches.pending(properties.batchSize())) {
            int messages = 0;
            try {
                Chunk chunk;
                do {
                    chunk = notifier.sendNextChunk(productId);
                    messages += chunk.messages();
                } while (!chunk.done());
                if (messages > 0 || chunk.completed()) {
                    log.info("오픈 알림 productId={} messages={}", productId, messages);
                }
            } catch (RuntimeException e) {
                log.error("오픈 알림 발송 실패 — 다음 주기에 이어 간다 productId={} messages={}", productId, messages, e);
            }
        }
    }

    /** 창 안에서 보내지 않는 이유. 표시 문장({@link PreorderCampaignRepository#markOpenNotified})의 조건과 맞춘다. */
    enum SkipReason {
        HIDDEN, PAUSED;

        /** @return 보낼 회차면 null. 판매 상태를 아직 받지 못한 회차(null)는 막지 않는다 */
        static SkipReason of(PreorderCampaign campaign) {
            if (!campaign.isVisible()) {
                return HIDDEN;
            }
            return campaign.getProductStatus() == ProductStatus.PAUSED ? PAUSED : null;
        }
    }
}
