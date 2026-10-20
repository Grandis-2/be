package com.grandis.nova.preorder.campaign.application;

import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.preorder.campaign.ProductStatus;
import com.grandis.nova.preorder.campaign.domain.OpenAlertDispatchStore;
import com.grandis.nova.preorder.campaign.domain.OpenAlertDispatchStore.Cursor;
import com.grandis.nova.preorder.campaign.domain.OpenAlertStore;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaignRepository;
import com.grandis.nova.preorder.outbox.NotificationRequested;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 회차 하나의 오픈 알림을 두 단계로 보낸다.
 *
 * 1. 표시({@link #markOpened}): 회차 행 조건부 UPDATE 와 진행 행 넣기만 한 트랜잭션. 회차 행은 접수가 잠그는 행이라 여기서 오래 잡지 않는다.
 * 2. 묶음({@link #sendNextChunk}): 묶음마다 따로인 짧은 트랜잭션에서 진행 행을 잠그고, 회차가 아직 보낼 상태인지 다시 본 뒤 커서 다음
 *    신청자 1,000명을 읽어 아웃박스에 한 행 적고 커서를 옮긴다. 아웃박스와 커서가 같이 커밋되므로 묶음은 정확히 한 번 나가고,
 *    여러 인스턴스가 같은 회차를 이어도 진행 행에서 줄 선다.
 */
@Component
class OpenAlertNotifier {

    static final String ABANDONED_METRIC = "preorder.open_alert.abandoned";

    private static final Logger log = LoggerFactory.getLogger(OpenAlertNotifier.class);

    private final PreorderCampaignRepository campaigns;
    private final OpenAlertStore alerts;
    private final OpenAlertDispatchStore dispatches;
    private final OutboxWriter outboxWriter;
    private final OpenAlertProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    OpenAlertNotifier(PreorderCampaignRepository campaigns, OpenAlertStore alerts, OpenAlertDispatchStore dispatches,
                      OutboxWriter outboxWriter, OpenAlertProperties properties, MeterRegistry meterRegistry,
                      Clock clock) {
        this.campaigns = campaigns;
        this.alerts = alerts;
        this.dispatches = dispatches;
        this.outboxWriter = outboxWriter;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * 열렸고 공개 · 판매 중이며 아직 보내지 않은 회차면 표시하고 발송을 시작한다. 비공개 · 판매 중지면 표시하지 않는다 —
     * 창(since) 안에 다시 공개 · 판매되면 그때 보낸다.
     *
     * @param since 이 시각 뒤에 연 회차만
     * @return 표시했으면 true
     */
    @Transactional
    public boolean markOpened(UUID productId, Instant now, Instant since) {
        if (campaigns.markOpenNotified(productId, now, since, ProductStatus.PAUSED) != 1) {
            return false;
        }
        dispatches.start(productId, now);
        return true;
    }

    /**
     * 다음 묶음 하나를 보낸다. 1,000명이 안 차면 마지막 묶음으로 보고 발송을 끝낸다.
     * 회차가 더 보낼 상태가 아니면({@link AbandonReason}) 보내지 않고 끝낸다 — 긴 장애 뒤에 이어 가다 마감 · 취소된 회차에
     * "시작됐어요" 가 나가지 않게. 시각은 주기 시작이 아니라 잠근 뒤에 잰다 — 앞 회차 발송이 길었을 수 있다.
     */
    @Transactional
    public Chunk sendNextChunk(UUID productId) {
        Optional<Cursor> cursor = dispatches.lock(productId);
        if (cursor.isEmpty() || cursor.get().completed()) {
            return Chunk.NONE;
        }
        Instant now = clock.instant();
        // 잠그지 않고 읽는다 — 회차 행은 접수가 잠그는 행이다. 상품 수정은 오픈 3분 전부터 막혀 상품명은 발송 중에 바뀌지 않는다
        PreorderCampaign campaign = campaigns.findById(productId)
                .orElseThrow(() -> new IllegalStateException("진행 행의 회차가 없다: productId=" + productId));
        UUID after = cursor.get().lastCustomerId();
        AbandonReason abandoned = AbandonReason.of(campaign, now, now.minus(properties.window()));
        if (abandoned != null) {
            dispatches.advance(productId, after, now);
            meterRegistry.counter(ABANDONED_METRIC, "reason", abandoned.name()).increment();
            log.warn("오픈 알림 발송을 남은 신청자에게 보내지 않고 끝낸다 productId={} reason={} afterCustomerId={}",
                    productId, abandoned, after);
            return Chunk.NONE;
        }
        List<UUID> recipients = alerts.recipients(productId, after, NotificationRequested.MAX_RECIPIENTS);
        boolean last = recipients.size() < NotificationRequested.MAX_RECIPIENTS;
        if (!recipients.isEmpty()) {
            outboxWriter.append(NotificationRequested.preorderOpened(productId, title(campaign), recipients));
            after = recipients.getLast();
        }
        dispatches.advance(productId, after, last ? now : null);
        return new Chunk(recipients.isEmpty() ? 0 : 1, last, last);
    }

    private static String title(PreorderCampaign campaign) {
        String title = campaign.getProductTitle();
        if (title == null) {
            log.warn("상품명 사본이 없어 상품명 없는 오픈 알림을 보낸다 productId={}", campaign.getProductId());
        }
        return title;
    }

    /**
     * @param messages  이번에 적은 메시지 수(0 또는 1)
     * @param done      더 보낼 묶음이 없다
     * @param completed 이 호출이 마지막 묶음으로 발송을 끝냈다. 포기(WARN 으로 따로 남긴다) · 이미 끝난 발송은 false
     */
    record Chunk(int messages, boolean done, boolean completed) {
        static final Chunk NONE = new Chunk(0, true, false);
    }

    /** 표시 뒤 발송을 이어 가다 그만두는 이유. 창 · 공개 · 판매 조건은 표시({@link PreorderCampaignRepository#markOpenNotified})와 맞춘다. */
    enum AbandonReason {
        CLOSED, WINDOW_PASSED, HIDDEN, PAUSED;

        /** @return 계속 보낼 회차면 null. 판매 상태를 아직 받지 못한 회차(null)는 막지 않는다 */
        static AbandonReason of(PreorderCampaign campaign, Instant now, Instant since) {
            if (!campaign.getClosesAt().isAfter(now)) {
                return CLOSED;
            }
            if (!campaign.getOpensAt().isAfter(since)) {
                return WINDOW_PASSED;
            }
            if (!campaign.isVisible()) {
                return HIDDEN;
            }
            return campaign.getProductStatus() == ProductStatus.PAUSED ? PAUSED : null;
        }
    }
}
