package com.grandis.nova.preorder.outbox;

import com.grandis.nova.common.outbox.OutboundAttributes;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Set;

/**
 * worker 로 가는 작업 메시지에 진행 속성을 싣는다. worker 는 차례 · 세대를 메시지 속성으로 이어 가므로
 * preorder 가 내보낼 때는 늘 처음(1 · 1)이다. actor · 실행 회차(run)는 payload 에 실린 값을 옮긴다(없으면 SYSTEM · 1).
 * 묶음 id 는 상품 id 다 — 작업 큐가 사전예약마다 공정하게 나눠 준다(worker 의 지연 재전송도 잇는다).
 */
@Component
class JobAttributes implements OutboundAttributes {

    private static final Set<String> JOB_EVENTS = Set.of(
            OutboundEventType.REGISTER_JOB_READY.name(), OutboundEventType.CANCEL_JOB_READY.name());
    private static final String FIRST = "1";
    private static final String DEFAULT_ACTOR = "SYSTEM";

    @Override
    public Map<String, String> of(String eventType, JsonNode payload) {
        if (!JOB_EVENTS.contains(eventType)) {
            return Map.of();
        }
        JsonNode actor = payload.get("actor");
        JsonNode run = payload.get("run");
        return Map.of("attempt", FIRST, "generation", FIRST,
                "run", run != null && run.isInt() ? Integer.toString(run.intValue()) : FIRST,
                "actor", actor != null && actor.isString() ? actor.stringValue() : DEFAULT_ACTOR);
    }

    @Override
    public String messageGroupId(String eventType, JsonNode payload) {
        if (!JOB_EVENTS.contains(eventType)) {
            return null;
        }
        JsonNode productId = payload.get("productId");
        return productId != null && productId.isString() ? productId.stringValue() : null;
    }
}
