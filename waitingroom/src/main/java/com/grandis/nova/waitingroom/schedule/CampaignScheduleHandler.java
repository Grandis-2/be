package com.grandis.nova.waitingroom.schedule;

import com.grandis.nova.common.message.EventEnvelope;
import com.grandis.nova.common.sqs.QueueMessageHandler;
import com.grandis.nova.waitingroom.domain.product.SalesWindow;
import com.grandis.nova.waitingroom.redis.ControlStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;

/**
 * 회차 일정 이벤트를 Redis 일정 목록에 반영한다. 일정 번호가 더 클 때만 쓰므로 순서가 뒤집히거나 두 번 와도 된다.
 * 읽을 수 없는 메시지 · Redis 실패는 던진다 — 지우지 않고 다시 받으며, 계속 실패하면 DLQ 로 간다.
 */
@Component
class CampaignScheduleHandler implements QueueMessageHandler {

    static final String CAMPAIGN_CHANGED = "PREORDER_CAMPAIGN_CHANGED";
    private static final Logger log = LoggerFactory.getLogger(CampaignScheduleHandler.class);
    /** 소비 스레드가 Redis 를 기다리는 한도. 메시지 가시성 시간보다 짧다. */
    private static final Duration APPLY_TIMEOUT = Duration.ofSeconds(5);

    private final ControlStore store;
    private final JsonMapper jsonMapper;
    private final MeterRegistry registry;

    CampaignScheduleHandler(ControlStore store, JsonMapper jsonMapper, MeterRegistry registry) {
        this.store = store;
        this.jsonMapper = jsonMapper;
        this.registry = registry;
    }

    @Override
    public void handle(Message message) {
        String outcome = "FAILED";
        try {
            EventEnvelope envelope = jsonMapper.readValue(message.body(), EventEnvelope.class);
            if (!CAMPAIGN_CHANGED.equals(envelope.eventType())) {
                throw new IllegalArgumentException("받지 않는 이벤트다: " + envelope.eventType());
            }
            CampaignChanged changed = jsonMapper.treeToValue(envelope.payload(), CampaignChanged.class);
            Boolean applied = store.applySchedule(String.valueOf(changed.productId()),
                    new SalesWindow(changed.opensAt(), changed.closesAt()), changed.scheduleVersion(), changed.isVisible())
                    .block(APPLY_TIMEOUT);
            // 답이 없으면 반영됐는지 모른다 — 옛 번호로 단정해 지우지 않고 다시 받는다
            if (applied == null) {
                throw new IllegalStateException("일정 반영 결과를 받지 못했다: productId=" + changed.productId());
            }
            boolean written = applied;
            outcome = written ? "APPLIED" : "STALE";
            log.info("회차 일정 {} productId={} scheduleVersion={} visible={} change={}", written ? "반영" : "무시(옛 번호)",
                    changed.productId(), changed.scheduleVersion(), changed.isVisible(), changed.change());
        } finally {
            Counter.builder("waitingroom.schedule.events").tag("outcome", outcome).register(registry).increment();
        }
    }

    /**
     * preorder 의 PREORDER_CAMPAIGN_CHANGED payload. change 는 로그용이고 판정은 일정 번호로 한다.
     * visible 이 없으면(이 칸을 모르는 preorder) 공개로 본다.
     */
    record CampaignChanged(Long productId, long scheduleVersion, Instant opensAt, Instant closesAt,
                           Instant changedAt, String change, Boolean visible) {

        boolean isVisible() {
            return !Boolean.FALSE.equals(visible);
        }
    }
}
