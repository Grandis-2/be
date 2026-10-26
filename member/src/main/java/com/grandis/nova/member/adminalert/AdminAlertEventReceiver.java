package com.grandis.nova.member.adminalert;

import com.grandis.nova.common.message.EventEnvelope;
import com.grandis.nova.member.adminalert.AdminAlertRejected.Reason;
import com.grandis.nova.member.adminalert.AdminAlertRules.Source;
import com.grandis.nova.member.adminalert.AdminAlertRules.TargetKind;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Set;

/**
 * admin-alert 큐의 ADMIN_ALERT_REQUESTED(contracts/admin-alert-events.md) 하나를 {@link AdminAlertSignal} 로 풀어 저장한다.
 * 검증 순서와 거절 방식은 회원 알림의 수신과 같다 — 계약 위반은 {@link AdminAlertRejected}, 저장 중 오류는 그대로 올린다. 표의 칸에 담기지 않는 값은
 * 여기서 거른다 — 넣다 실패하면 이유 없이 재수신을 돌다 DLQ 로 간다.
 *
 * 사건의 이름(episode)은 PREORDER_* 가 metrics.campaignId, DRAW_* 가 회차 id, 모르는 type 은 eventId 다. 같은 사건을 다시 받으면 같은 행이다.
 */
@Component
class AdminAlertEventReceiver {

    static final String EVENT_TYPE = "ADMIN_ALERT_REQUESTED";
    static final int EVENT_ID_LENGTH = 36;
    static final int TYPE_LENGTH = 40;
    static final int TARGET_ID_LENGTH = 100;
    static final int EPISODE_LENGTH = 64;
    /** datetime(6) 이 담는 범위 */
    static final Instant EARLIEST = Instant.parse("1000-01-01T00:00:00Z");
    static final Instant LIMIT = Instant.parse("+10000-01-01T00:00:00Z");
    private static final Set<String> CAMPAIGN_TYPES = Set.of("PREORDER_OPENED", "PREORDER_CLOSED");
    private static final Set<String> DRAW_TYPES = Set.of("DRAW_SETTLED", "DRAW_DELAYED");

    private final AdminAlertIntake intake;
    private final JsonMapper jsonMapper;

    AdminAlertEventReceiver(AdminAlertIntake intake, JsonMapper jsonMapper) {
        this.intake = intake;
        this.jsonMapper = jsonMapper;
    }

    /**
     * @throws AdminAlertRejected 풀 수 없거나 계약에 맞지 않는 메시지
     * @throws RuntimeException   저장 중 오류(다시 받으면 될 수 있다)
     */
    void receive(String body) {
        intake.accept(parse(body));
    }

    AdminAlertSignal parse(String body) {
        EventEnvelope envelope = readEnvelope(body);
        if (!EVENT_TYPE.equals(envelope.eventType())) {
            throw new AdminAlertRejected(Reason.NOT_ADMIN_ALERT, "관리자 알림 요청이 아니다: eventType=" + envelope.eventType());
        }
        String eventId = envelope.eventId();
        Instant occurredAt = envelope.occurredAt();
        // aggregateType · aggregateId 는 저장도 기록도 하지 않아 보지 않는다(aggregateId 가 UUID 가 아니면 봉투를 풀 때 UNREADABLE 이다)
        if (eventId == null || eventId.isBlank() || eventId.length() > EVENT_ID_LENGTH || occurredAt == null
                || occurredAt.isBefore(EARLIEST) || !occurredAt.isBefore(LIMIT)) {
            throw new AdminAlertRejected(Reason.INVALID_ENVELOPE, "봉투의 eventId · occurredAt 이 계약에 맞지 않는다: eventId=" + eventId);
        }
        JsonNode payload = envelope.payload();
        if (payload == null || !payload.isObject()) {
            throw invalid("payload 가 없다", eventId);
        }
        String type = text(payload.get("type"));
        if (type == null || type.length() > TYPE_LENGTH) {
            throw invalid("type 이 비었거나 " + TYPE_LENGTH + "자를 넘는다", eventId);
        }
        JsonNode target = payload.get("target");
        TargetKind kind = target == null ? null : kindOf(text(target.get("kind")));
        String targetId = target == null ? null : text(target.get("id"));
        if (kind == null || targetId == null || targetId.length() > TARGET_ID_LENGTH) {
            throw invalid("target.kind 는 PRODUCT · DRAW, target.id 는 1~" + TARGET_ID_LENGTH + "자다", eventId);
        }
        if (!(payload.get("metrics") instanceof ObjectNode metrics)) {
            throw invalid("metrics 가 객체가 아니다", eventId);
        }
        if (!AdminAlertMetrics.storable(metrics)) {
            throw invalid("metrics 에 JSON 칸이 담을 수 없는 숫자(double 범위 밖)가 있다", eventId);
        }
        String episode = episode(type, kind, targetId, metrics, eventId);
        return new AdminAlertSignal(Source.EVENT, type, kind, targetId, episode, false, occurredAt, null, occurredAt, metrics, eventId);
    }

    private static String episode(String type, TargetKind kind, String targetId, ObjectNode metrics, String eventId) {
        if (CAMPAIGN_TYPES.contains(type)) {
            String campaignId = text(metrics.get("campaignId"));
            if (campaignId == null || campaignId.length() > EPISODE_LENGTH) {
                throw invalid(type + " 의 metrics.campaignId 가 없다", eventId);
            }
            return campaignId;
        }
        if (DRAW_TYPES.contains(type)) {
            if (kind != TargetKind.DRAW || targetId.length() > EPISODE_LENGTH) {
                throw invalid(type + " 의 target 은 DRAW 회차여야 한다", eventId);
            }
            return targetId;
        }
        return eventId;
    }

    /** 본문이 JSON 리터럴 null 이면 Jackson 은 예외 없이 null 을 돌려준다 — 그것도 풀 수 없는 것으로 본다. */
    private EventEnvelope readEnvelope(String body) {
        EventEnvelope envelope;
        try {
            envelope = jsonMapper.readValue(body, EventEnvelope.class);
        } catch (JacksonException e) {
            throw new AdminAlertRejected(Reason.UNREADABLE, "봉투를 풀 수 없다", e);
        }
        if (envelope == null) {
            throw new AdminAlertRejected(Reason.UNREADABLE, "본문이 null 이다");
        }
        return envelope;
    }

    private static TargetKind kindOf(String kind) {
        return "PRODUCT".equals(kind) ? TargetKind.PRODUCT : "DRAW".equals(kind) ? TargetKind.DRAW : null;
    }

    /** 문자열이고 비지 않았으면 그 값, 아니면 null. */
    static String text(JsonNode node) {
        return node != null && node.isString() && !node.asString().isBlank() ? node.asString() : null;
    }

    private static AdminAlertRejected invalid(String why, String eventId) {
        return new AdminAlertRejected(Reason.INVALID_PAYLOAD, "payload 가 계약에 맞지 않는다(" + why + "): eventId=" + eventId);
    }
}
