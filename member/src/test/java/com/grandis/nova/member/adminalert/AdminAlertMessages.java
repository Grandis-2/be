package com.grandis.nova.member.adminalert;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;
import java.util.function.Consumer;

/** 시험용 관리자 알림 메시지. 기본값은 계약 문서의 예시와 같은 모양이다. 고칠 칸은 with 로 바꾼다. */
final class AdminAlertMessages {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private AdminAlertMessages() {
    }

    /** admin-alert 큐의 ADMIN_ALERT_REQUESTED 봉투. */
    static final class Event {

        final String eventId = UUID.randomUUID().toString();
        final ObjectNode envelope = JSON.createObjectNode();

        Event(String type, String kind, String targetId, String occurredAt) {
            envelope.put("eventId", eventId);
            envelope.put("eventType", "ADMIN_ALERT_REQUESTED");
            envelope.put("aggregateType", "DRAW".equals(kind) ? "DRAW_CAMPAIGN" : "PREORDER_CAMPAIGN");
            envelope.put("aggregateId", UUID.randomUUID().toString());
            envelope.put("occurredAt", occurredAt);
            ObjectNode payload = envelope.putObject("payload");
            payload.put("type", type);
            payload.putObject("target").put("kind", kind).put("id", targetId);
            payload.putObject("metrics");
        }

        static Event draw(String type, UUID drawId, String occurredAt) {
            return new Event(type, "DRAW", drawId.toString(), occurredAt);
        }

        static Event preorder(String type, UUID productId, UUID campaignId, String occurredAt) {
            Event event = new Event(type, "PRODUCT", productId.toString(), occurredAt);
            event.metrics().put("campaignId", campaignId.toString());
            return event;
        }

        ObjectNode payload() {
            return (ObjectNode) envelope.get("payload");
        }

        ObjectNode metrics() {
            return (ObjectNode) payload().get("metrics");
        }

        Event with(Consumer<Event> change) {
            change.accept(this);
            return this;
        }

        String body() {
            return JSON.writeValueAsString(envelope);
        }
    }

    /** Grafana 큐의 메시지(경보 하나). */
    static final class Grafana {

        final ObjectNode message = JSON.createObjectNode();
        final ObjectNode alert;

        Grafana(String status, String type, String startsAt) {
            message.put("version", "1");
            ArrayNode alerts = message.putArray("alerts");
            alert = alerts.addObject();
            alert.put("status", status);
            alert.put("fingerprint", "8f3c2b1a9d0e4f57");
            alert.put("startsAt", startsAt);
            alert.put("endsAt", "0001-01-01T00:00:00Z");
            ObjectNode labels = alert.putObject("labels");
            labels.put("alertname", "관리자 · " + type);
            labels.put("admin_alert_type", type);
            labels.put("admin_alert_target_kind", "NONE");
            labels.put("severity", "critical");
            alert.putObject("annotations").put("summary", "시험 경보");
        }

        static Grafana firing(String type, String startsAt) {
            return new Grafana("firing", type, startsAt);
        }

        static Grafana resolved(String type, String startsAt, String endsAt) {
            Grafana grafana = new Grafana("resolved", type, startsAt);
            grafana.alert.put("endsAt", endsAt);
            return grafana;
        }

        Grafana target(String kind, String id) {
            labels().put("admin_alert_target_kind", kind);
            labels().put("admin_alert_target_id", id);
            return this;
        }

        Grafana metric(String name, String value) {
            ((ObjectNode) alert.get("annotations")).put("metric_" + name, value);
            return this;
        }

        ObjectNode labels() {
            return (ObjectNode) alert.get("labels");
        }

        Grafana with(Consumer<Grafana> change) {
            change.accept(this);
            return this;
        }

        String body() {
            return JSON.writeValueAsString(message);
        }
    }
}
