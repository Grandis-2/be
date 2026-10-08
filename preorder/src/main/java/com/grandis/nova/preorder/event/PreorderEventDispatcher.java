package com.grandis.nova.preorder.event;

import com.grandis.nova.common.message.EventEnvelope;
import com.grandis.nova.preorder.campaign.CampaignRegistrar;
import com.grandis.nova.preorder.campaign.CampaignRepublisher;
import com.grandis.nova.preorder.campaign.Campaigns;
import com.grandis.nova.preorder.cancel.CampaignCancelService;
import com.grandis.nova.preorder.cancel.ExpiryCancelService;
import com.grandis.nova.preorder.integration.catalog.CatalogCacheInvalidation;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 받은 메시지를 이벤트 종류별 처리로 보낸다. 큐 소비기는 본문을 그대로 여기에 넘긴다.
 *
 * 모르는 종류 · 깨진 본문은 예외로 올린다. 소비기는 메시지를 지우지 않고, 재수신 한도를 넘으면 DLQ 로 간다.
 */
@Component
public class PreorderEventDispatcher {

    static final String HANDLE_METRIC = "preorder.events.handle";
    static final String LAG_METRIC = "preorder.events.lag";
    private static final String UNKNOWN = "UNKNOWN";
    private static final Logger log = LoggerFactory.getLogger(PreorderEventDispatcher.class);

    private final PreorderEventHandler handler;
    private final ExpiryCancelService expiryCancelService;
    private final CampaignCancelService campaignCancelService;
    private final CampaignRepublisher campaignRepublisher;
    private final CampaignRegistrar campaignRegistrar;
    private final Campaigns campaigns;
    private final CatalogCacheInvalidation catalogCache;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public PreorderEventDispatcher(PreorderEventHandler handler, ExpiryCancelService expiryCancelService,
                                   CampaignCancelService campaignCancelService, CampaignRepublisher campaignRepublisher,
                                   CampaignRegistrar campaignRegistrar, Campaigns campaigns,
                                   CatalogCacheInvalidation catalogCache, JsonMapper jsonMapper,
                                   MeterRegistry meterRegistry, Clock clock) {
        this.handler = handler;
        this.expiryCancelService = expiryCancelService;
        this.campaignCancelService = campaignCancelService;
        this.campaignRepublisher = campaignRepublisher;
        this.campaignRegistrar = campaignRegistrar;
        this.campaigns = campaigns;
        this.catalogCache = catalogCache;
        this.jsonMapper = jsonMapper;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * 처리 시간 · 결과를 종류별로 센다(읽을 수 없거나 모르는 종류는 UNKNOWN). 발생 시각부터 처리 시작까지를
     * 소비 지연으로 잰다 — 큐 적체 · 소비기 부족이 여기서 드러난다.
     *
     * @throws IllegalArgumentException 받지 않는 이벤트 종류
     */
    public void dispatch(String body) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String eventType = UNKNOWN;
        String outcome = "failure";
        try {
            EventEnvelope envelope = jsonMapper.readValue(body, EventEnvelope.class);
            InboundEventType type = InboundEventType.valueOf(envelope.eventType());
            eventType = type.name();
            recordLag(eventType, envelope.occurredAt());
            route(type, envelope);
            outcome = "success";
        } finally {
            sample.stop(meterRegistry.timer(HANDLE_METRIC, "eventType", eventType, "outcome", outcome));
        }
    }

    private void recordLag(String eventType, Instant occurredAt) {
        if (occurredAt != null) {
            // 발행 쪽 시계가 앞서 있으면 음수가 된다. 지연이 없는 것으로 본다
            Duration lag = Duration.between(occurredAt, clock.instant());
            meterRegistry.timer(LAG_METRIC, "eventType", eventType).record(lag.isNegative() ? Duration.ZERO : lag);
        }
    }

    private void route(InboundEventType type, EventEnvelope envelope) {
        switch (type) {
            case EXTERNAL_JOB_SUCCEEDED -> handler.onExternalJobSucceeded(
                    jsonMapper.treeToValue(envelope.payload(), ExternalJobSucceeded.class));
            case PREORDER_ORDER_SETTLED -> handler.onOrderSettled(
                    jsonMapper.treeToValue(envelope.payload(), PreorderOrderSettled.class));
            case PREORDER_PAYMENT_STARTED -> handler.onPaymentStarted(
                    jsonMapper.treeToValue(envelope.payload(), PreorderPaymentStarted.class));
            case PREORDER_PAYMENT_CONFIRMED -> handler.onPaymentConfirmed(
                    jsonMapper.treeToValue(envelope.payload(), PreorderPaymentConfirmed.class));
            case PREORDER_EXPIRY_REQUESTED -> expiryCancelService.expire(
                    jsonMapper.treeToValue(envelope.payload(), PreorderExpiryRequested.class).preorderId());
            case PREORDER_CAMPAIGN_CANCELED -> {
                PreorderCampaignCanceled canceled =
                        jsonMapper.treeToValue(envelope.payload(), PreorderCampaignCanceled.class);
                campaignCancelService.cancel(canceled.productId(), canceled.reason());
            }
            case CAMPAIGN_RESYNC_REQUESTED -> {
                CampaignResyncRequested requested =
                        jsonMapper.treeToValue(envelope.payload(), CampaignResyncRequested.class);
                log.info("회차 일정 전체 재발행 요청: requestedBy={}, reason={}", requested.requestedBy(), requested.reason());
                campaignRepublisher.republishAll();
            }
            case PREORDER_PRODUCT_CHANGED -> onProductChanged(requireProductId(envelope),
                    jsonMapper.treeToValue(envelope.payload(), PreorderProductChanged.class));
            case PREORDER_PRODUCT_REGISTERED -> {
                campaignRegistrar.register(requireProductId(envelope),
                        jsonMapper.treeToValue(envelope.payload(), PreorderProductRegistered.class).toRegistration());
            }
        }
    }

    /**
     * 오픈 직전 가격 변경이 캐시 갱신(1분)을 기다리지 않고 모든 인스턴스의 접수에 바로 반영되게 캐시를 먼저 비운다.
     * 공개 여부가 실려 있으면 회차에 반영한다 — 회차가 아직 없으면 예외로 다시 받는다.
     */
    private void onProductChanged(UUID productId, PreorderProductChanged changed) {
        catalogCache.evictEverywhere(productId);
        if (changed != null && changed.hasVisibility()) {
            campaigns.applyVisibility(productId, changed.visible(), changed.visibilityVersion());
        }
    }

    /** catalog 상품 이벤트는 상품 id 를 봉투의 aggregateId 로만 싣는다. */
    private UUID requireProductId(EventEnvelope envelope) {
        if (envelope.aggregateId() == null) {
            throw new IllegalArgumentException("상품 id(aggregateId)가 없는 상품 이벤트: " + envelope.eventType());
        }
        return envelope.aggregateId();
    }
}
