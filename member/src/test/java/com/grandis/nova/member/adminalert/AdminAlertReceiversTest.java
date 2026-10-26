package com.grandis.nova.member.adminalert;

import com.grandis.nova.member.adminalert.AdminAlertRejected.Reason;
import com.grandis.nova.member.adminalert.AdminAlertRules.Source;
import com.grandis.nova.member.adminalert.AdminAlertRules.TargetKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DisplayName("관리자 알림 메시지 풀기 — 계약 위반은 이유와 함께 거절한다")
class AdminAlertReceiversTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Instant NOW = Instant.parse("2026-10-26T05:10:00Z");
    static final String AT = "2026-10-26T05:02:11.123456Z";

    final AdminAlertEventReceiver events = new AdminAlertEventReceiver(mock(AdminAlertIntake.class), JSON);
    final GrafanaAlertReceiver grafana = new GrafanaAlertReceiver(mock(AdminAlertIntake.class), JSON, Clock.fixed(NOW, ZoneOffset.UTC));

    @Nested
    @DisplayName("admin-alert 큐(ADMIN_ALERT_REQUESTED)")
    class Events {

        @Test
        @DisplayName("PREORDER_* 의 사건 이름은 campaignId, DRAW_* 는 회차 id, 모르는 type 은 eventId 다")
        void episodes() {
            UUID product = UUID.randomUUID();
            UUID campaign = UUID.randomUUID();
            UUID draw = UUID.randomUUID();
            AdminAlertSignal opened = events.parse(AdminAlertMessages.Event.preorder("PREORDER_OPENED", product, campaign, AT).body());
            assertThat(opened.source()).isEqualTo(Source.EVENT);
            assertThat(opened.targetKind()).isEqualTo(TargetKind.PRODUCT);
            assertThat(opened.targetId()).isEqualTo(product.toString());
            assertThat(opened.episode()).isEqualTo(campaign.toString());
            assertThat(opened.occurredAt()).isEqualTo(Instant.parse(AT));

            assertThat(events.parse(AdminAlertMessages.Event.draw("DRAW_DELAYED", draw, AT).body()).episode()).isEqualTo(draw.toString());

            AdminAlertMessages.Event unknown = new AdminAlertMessages.Event("NEW_THING", "PRODUCT", product.toString(), AT);
            assertThat(events.parse(unknown.body()).episode()).isEqualTo(unknown.eventId);
        }

        @Test
        @DisplayName("JSON 이 아니면 UNREADABLE, 다른 eventType 이면 NOT_ADMIN_ALERT, 봉투 칸이 틀리면 INVALID_ENVELOPE")
        void envelope() {
            assertReason(() -> events.parse("{not json"), Reason.UNREADABLE);
            assertReason(() -> events.parse("null"), Reason.UNREADABLE);
            assertReason(() -> events.parse(draw().with(e -> e.envelope.put("eventType", "NOTIFICATION_REQUESTED")).body()),
                    Reason.NOT_ADMIN_ALERT);
            assertReason(() -> events.parse(draw().with(e -> e.envelope.remove("occurredAt")).body()), Reason.INVALID_ENVELOPE);
            assertReason(() -> events.parse(draw().with(e -> e.envelope.put("eventId", "x".repeat(37))).body()), Reason.INVALID_ENVELOPE);
            assertReason(() -> events.parse(draw().with(e -> e.envelope.put("occurredAt", "0999-12-31T23:59:59Z")).body()),
                    Reason.INVALID_ENVELOPE);
            assertThat(events.parse(draw().with(e -> e.envelope.put("aggregateType", "X".repeat(31))).body()))
                    .as("aggregateType 은 저장하지 않아 길이를 보지 않는다").isNotNull();
        }

        @Test
        @DisplayName("type · target · metrics · campaignId 가 계약에 맞지 않으면 INVALID_PAYLOAD")
        void payload() {
            assertReason(() -> events.parse(draw().with(e -> e.payload().put("type", " ")).body()), Reason.INVALID_PAYLOAD);
            assertReason(() -> events.parse(draw().with(e -> e.payload().put("type", "X".repeat(41))).body()), Reason.INVALID_PAYLOAD);
            assertReason(() -> events.parse(draw().with(e -> e.payload().putObject("target").put("kind", "SERVICE").put("id", "order"))
                    .body()), Reason.INVALID_PAYLOAD);
            assertReason(() -> events.parse(draw().with(e -> e.payload().put("metrics", 1)).body()), Reason.INVALID_PAYLOAD);
            assertReason(() -> events.parse(draw().body().replace("\"metrics\":{}", "\"metrics\":{\"winners\":1e400}")),
                    Reason.INVALID_PAYLOAD);
            assertReason(() -> events.parse(AdminAlertMessages.Event.preorder("PREORDER_OPENED", UUID.randomUUID(), UUID.randomUUID(), AT)
                    .with(e -> e.metrics().put("campaignId", "c".repeat(65))).body()), Reason.INVALID_PAYLOAD);
            assertReason(() -> events.parse(AdminAlertMessages.Event.preorder("PREORDER_CLOSED", UUID.randomUUID(), UUID.randomUUID(), AT)
                    .with(e -> e.metrics().remove("campaignId")).body()), Reason.INVALID_PAYLOAD);
            assertReason(() -> events.parse(new AdminAlertMessages.Event("DRAW_SETTLED", "PRODUCT", UUID.randomUUID().toString(), AT)
                    .body()), Reason.INVALID_PAYLOAD);
        }

        private AdminAlertMessages.Event draw() {
            return AdminAlertMessages.Event.draw("DRAW_SETTLED", UUID.randomUUID(), AT);
        }
    }

    @Nested
    @DisplayName("Grafana 큐")
    class Grafana {

        @Test
        @DisplayName("firing 경보 — 사건 이름은 startsAt, 대상 라벨이 NONE 이면 대상 id 는 빈 값, metric_* 만 metrics 로 옮긴다")
        void firing() {
            List<AdminAlertSignal> signals = grafana.parse(AdminAlertMessages.Grafana.firing("REGISTER_FAILED", "2026-10-26T05:02:11Z")
                    .metric("deadLetters", "860").metric("rate", "0.25").metric("route", "POST /orders").body());

            AdminAlertSignal signal = signals.getFirst();
            assertThat(signal.source()).isEqualTo(Source.GRAFANA);
            assertThat(signal.type()).isEqualTo("REGISTER_FAILED");
            assertThat(signal.targetKind()).isEqualTo(TargetKind.NONE);
            assertThat(signal.targetId()).isEmpty();
            assertThat(signal.episode()).isEqualTo("2026-10-26T05:02:11Z");
            assertThat(signal.resolved()).isFalse();
            assertThat(signal.resolvedAt()).isNull();
            assertThat(signal.seenAt()).isEqualTo(NOW);
            Instant sent = NOW.minusSeconds(90);
            assertThat(grafana.parse(AdminAlertMessages.Grafana.firing("REGISTER_FAILED", "2026-10-26T05:02:11Z").body(), sent).getFirst()
                    .seenAt()).as("SQS 가 받은 시각이 있으면 그것").isEqualTo(sent);
            assertThat(signal.metrics().propertyNames()).containsExactlyInAnyOrder("deadLetters", "rate", "route");
            assertThat(signal.metrics().get("deadLetters").isNumber()).isTrue();
            assertThat(signal.metrics().get("deadLetters").decimalValue()).isEqualByComparingTo("860");
            assertThat(signal.metrics().get("route").asString()).isEqualTo("POST /orders");
        }

        @Test
        @DisplayName("값이 없음을 뜻하는 글자(빈 값 · NaN · ±Inf)는 null, 음수는 숫자 그대로")
        void noValue() {
            AdminAlertSignal signal = grafana.parse(AdminAlertMessages.Grafana.firing("QUEUE_SERVER_UNHEALTHY", "2026-10-26T05:02:11Z")
                    .metric("a", "").metric("b", "NaN").metric("c", "+Inf").metric("d", "-Inf").metric("e", "<nil>")
                    .metric("snapshotAgeSeconds", "-1").body()).getFirst();

            for (String name : List.of("a", "b", "c", "d", "e")) {
                assertThat(signal.metrics().get(name).isNull()).as(name).isTrue();
            }
            assertThat(signal.metrics().get("snapshotAgeSeconds").decimalValue()).isEqualByComparingTo("-1");
        }

        @Test
        @DisplayName("resolved 경보는 endsAt 이 해소 시각이다. SERVICE 대상은 target_id 라벨을 쓴다")
        void resolved() {
            AdminAlertSignal signal = grafana.parse(AdminAlertMessages.Grafana.resolved("SERVICE_INCIDENT", "2026-10-26T05:02:11Z",
                    "2026-10-26T05:09:00Z").target("SERVICE", "order").body()).getFirst();

            assertThat(signal.resolved()).isTrue();
            assertThat(signal.resolvedAt()).isEqualTo(Instant.parse("2026-10-26T05:09:00Z"));
            assertThat(signal.targetKind()).isEqualTo(TargetKind.SERVICE);
            assertThat(signal.targetId()).isEqualTo("order");
        }

        @Test
        @DisplayName("대상 종류 라벨이 없으면 NONE 으로 읽는다")
        void kindMissing() {
            assertThat(grafana.parse(AdminAlertMessages.Grafana.firing("SHIPPING_BACKLOG", "2026-10-26T05:02:11Z")
                    .with(g -> g.labels().remove("admin_alert_target_kind")).body()).getFirst().targetKind()).isEqualTo(TargetKind.NONE);
        }

        @Test
        @DisplayName("version · alerts 가 틀리면 INVALID_MESSAGE, 경보 하나의 status · 라벨 · 시각이 틀리면 INVALID_ALERT")
        void violations() {
            assertReason(() -> grafana.parse("[]"), Reason.UNREADABLE);
            assertReason(() -> grafana.parse(alert().with(g -> g.message.put("version", "2")).body()), Reason.INVALID_MESSAGE);
            assertReason(() -> grafana.parse(alert().with(g -> g.message.putArray("alerts")).body()), Reason.INVALID_MESSAGE);
            assertReason(() -> grafana.parse(alert().with(g -> g.alert.put("status", "pending")).body()), Reason.INVALID_ALERT);
            assertReason(() -> grafana.parse(alert().with(g -> g.labels().remove("admin_alert_type")).body()), Reason.INVALID_ALERT);
            assertReason(() -> grafana.parse(alert().with(g -> g.labels().put("admin_alert_target_kind", "USER")).body()),
                    Reason.INVALID_ALERT);
            assertReason(() -> grafana.parse(alert().with(g -> g.labels().put("admin_alert_target_kind", "SERVICE")).body()),
                    Reason.INVALID_ALERT);
            assertReason(() -> grafana.parse(alert().with(g -> g.alert.put("startsAt", "어제")).body()), Reason.INVALID_ALERT);
            assertReason(() -> grafana.parse(alert().with(g -> g.alert.put("startsAt", "0001-01-01T00:00:00Z")).body()),
                    Reason.INVALID_ALERT);
            assertReason(() -> grafana.parse(alert().with(g -> g.alert.put("endsAt", "곧")).body()), Reason.INVALID_ALERT);
            assertReason(() -> grafana.parse(alert().with(g -> g.message.withArray("alerts").addNull()).body()), Reason.INVALID_ALERT);
            assertReason(() -> grafana.parse(alert().with(g -> g.message.putArray("alerts").add(1)).body()), Reason.INVALID_ALERT);
            assertReason(() -> grafana.parse(AdminAlertMessages.Grafana.resolved("REGISTER_FAILED", "2026-10-26T05:02:11Z",
                    "0001-01-01T00:00:00Z").body()), Reason.INVALID_ALERT);
            assertReason(() -> grafana.parse(AdminAlertMessages.Grafana.resolved("REGISTER_FAILED", "2026-10-26T05:02:11Z", "x")
                    .with(g -> g.alert.remove("endsAt")).body()), Reason.INVALID_ALERT);
            assertReason(() -> grafana.parse(alert().metric("deadLetters", "1e17").body()), Reason.INVALID_ALERT);
            assertThat(grafana.parse(alert().metric("deadLetters", "1e17").with(g -> g.labels().put("admin_alert_type", "QUEUE_BRAKING"))
                    .body())).as("주 숫자가 아닌 칸은 decimal 칸에 넣지 않아 ±10^16 을 묻지 않는다 — double 범위만 본다").hasSize(1);
            assertReason(() -> grafana.parse(alert().metric("other", "1e400").body()), Reason.INVALID_ALERT);
        }

        @Test
        @DisplayName("주 숫자 범위는 칸의 자리(소수 4자리)로 반올림한 값으로 본다 — 10^16 바로 아래는 받고, 반올림해 10^16 이 되면 거절한다")
        void primaryBoundary() {
            assertThat(grafana.parse(alert().metric("deadLetters", "9999999999999999.9999").body())).hasSize(1);
            assertThat(grafana.parse(alert().metric("deadLetters", "-9999999999999999.9999").body())).hasSize(1);
            assertReason(() -> grafana.parse(alert().metric("deadLetters", "9999999999999999.99995").body()), Reason.INVALID_ALERT);
            assertReason(() -> grafana.parse(alert().metric("deadLetters", "10000000000000000").body()), Reason.INVALID_ALERT);
        }

        private AdminAlertMessages.Grafana alert() {
            return AdminAlertMessages.Grafana.firing("REGISTER_FAILED", "2026-10-26T05:02:11Z");
        }
    }

    private static void assertReason(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, Reason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(AdminAlertRejected.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
