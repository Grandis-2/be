package com.grandis.nova.preorder.event;

import com.grandis.nova.preorder.cancel.CampaignCancelService;
import com.grandis.nova.preorder.cancel.ExpiryCancelService;
import com.grandis.nova.preorder.outbox.EventEnvelope;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

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

    private final PreorderEventHandler handler;
    private final ExpiryCancelService expiryCancelService;
    private final CampaignCancelService campaignCancelService;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public PreorderEventDispatcher(PreorderEventHandler handler, ExpiryCancelService expiryCancelService,
                                   CampaignCancelService campaignCancelService, JsonMapper jsonMapper,
                                   MeterRegistry meterRegistry, Clock clock) {
        this.handler = handler;
        this.expiryCancelService = expiryCancelService;
        this.campaignCancelService = campaignCancelService;
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
            case PREORDER_EXPIRY_REQUESTED -> expiryCancelService.expire(
                    jsonMapper.treeToValue(envelope.payload(), PreorderExpiryRequested.class).preorderId());
            case PREORDER_CAMPAIGN_CANCELED -> {
                PreorderCampaignCanceled canceled =
                        jsonMapper.treeToValue(envelope.payload(), PreorderCampaignCanceled.class);
                campaignCancelService.cancel(canceled.productId(), canceled.reason());
            }
        }
    }
}
