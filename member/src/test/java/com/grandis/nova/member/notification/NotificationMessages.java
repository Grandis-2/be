package com.grandis.nova.member.notification;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 시험용 NOTIFICATION_REQUESTED 본문. 계약 문서의 예와 같은 모양에서 시작해 고칠 칸만 고친다.
 * eventId · kind 는 매번 새로 만든다 — 시험끼리 같은 표 · 지표를 쓰므로 자기 행과 자기 지표만 세게.
 */
final class NotificationMessages {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    final String eventId = UUID.randomUUID().toString();
    final String kind = "TEST_" + UUID.randomUUID().toString().substring(0, 8);
    final ObjectNode envelope;

    NotificationMessages(UUID... recipientIds) {
        envelope = JSON.createObjectNode();
        envelope.put("eventId", eventId);
        envelope.put("eventType", "NOTIFICATION_REQUESTED");
        envelope.put("aggregateType", "PREORDER");
        envelope.put("aggregateId", "0199a3f2-8a10-7b21-9c32-4d5e6f708192");
        envelope.put("occurredAt", "2026-10-15T03:00:00.123456Z");
        ObjectNode payload = envelope.putObject("payload");
        ArrayNode recipients = payload.putArray("recipientIds");
        for (UUID recipientId : recipientIds) {
            recipients.add(recipientId.toString());
        }
        payload.put("kind", kind);
        payload.put("title", "사전예약 등록 완료");
        payload.put("body", "사전예약 등록이 완료됐어요. 24시간 안에 결제해 주세요.");
        ObjectNode target = payload.putObject("target");
        target.put("type", "PREORDER");
        target.put("id", "3c0f6a52-6d1b-4f7e-9b2a-0e5d8c1f4a77");
    }

    /** 발생 시각을 바꾼다 — 목록 순서를 보는 시험용 */
    NotificationMessages occurredAt(String instant) {
        envelope.put("occurredAt", instant);
        return this;
    }

    ObjectNode payload() {
        return (ObjectNode) envelope.get("payload");
    }

    NotificationMessages with(Consumer<NotificationMessages> change) {
        change.accept(this);
        return this;
    }

    String body() {
        return JSON.writeValueAsString(envelope);
    }
}
