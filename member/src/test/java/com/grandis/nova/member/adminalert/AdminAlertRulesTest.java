package com.grandis.nova.member.adminalert;

import com.grandis.nova.member.adminalert.AdminAlertRules.Audience;
import com.grandis.nova.member.adminalert.AdminAlertRules.Lifecycle;
import com.grandis.nova.member.adminalert.AdminAlertRules.Severity;
import com.grandis.nova.member.adminalert.AdminAlertRules.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("관리자 알림 규칙 — type 표 · 재알림 판정")
class AdminAlertRulesTest {

    static final Instant RAISED = Instant.parse("2026-10-26T05:00:00Z");

    /** 계약(contracts/admin-alert-hub.md "type 규칙")의 16행. 받는 사람 · 심각도 · 해소 방식 · 주 숫자 · 승격. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
            REGISTER_FAILED        | ADMIN           | URGENT | ONGOING | deadLetters | -
            EVENT_DLQ_WAITING      | DEVELOPER       | CHECK  | ONGOING | waiting     | -
            QUEUE_BRAKING          | DEVELOPER       | URGENT | ONGOING | -           | -
            QUEUE_SERVER_UNHEALTHY | ADMIN           | CHECK  | ONGOING | -           | 5
            EXTERNAL_SYSTEM_DOWN   | ADMIN           | URGENT | ONGOING | -           | -
            SERVICE_INCIDENT       | ADMIN DEVELOPER | URGENT | ONGOING | -           | -
            SERVICE_ERROR_SPIKE    | DEVELOPER       | URGENT | ONGOING | -           | -
            SHIPPING_BACKLOG       | ADMIN           | INFO   | ONGOING | total       | -
            PREORDER_OPENED        | ADMIN           | INFO   | ONCE    | -           | -
            PREORDER_CLOSED        | ADMIN           | INFO   | ONCE    | -           | -
            DRAW_SETTLED           | ADMIN           | INFO   | ONCE    | -           | -
            DRAW_DELAYED           | ADMIN           | CHECK  | ONGOING | -           | -
            ADMISSION_STOPPED      | ADMIN DEVELOPER | URGENT | ONGOING | waiting     | -
            QUEUE_LONG_WAIT        | ADMIN           | CHECK  | ONGOING | -           | -
            PAYMENT_FAILURE_SPIKE  | DEVELOPER       | CHECK  | ONGOING | -           | -
            RECONCILE_MISMATCH     | ADMIN DEVELOPER | CHECK  | ONGOING | count       | -
            """)
    @DisplayName("16종의 규칙이 계약 표와 같다")
    void table(String type, String audiences, Severity severity, Lifecycle lifecycle, String primary, Integer escalateMinutes) {
        AdminAlertRules.Rule rule = AdminAlertRules.RULES.get(type);
        assertThat(rule).isNotNull();
        assertThat(rule.audiences()).containsExactlyElementsOf(Arrays.stream(audiences.split(" ")).map(Audience::valueOf).toList());
        assertThat(rule.severity()).isEqualTo(severity);
        assertThat(rule.lifecycle()).isEqualTo(lifecycle);
        assertThat(rule.primaryMetric()).isEqualTo(primary);
        assertThat(rule.escalateAfter()).isEqualTo(escalateMinutes == null ? null : Duration.ofMinutes(escalateMinutes));
    }

    @Test
    @DisplayName("표에는 그 16종만 있다")
    void onlySixteen() {
        assertThat(AdminAlertRules.RULES).hasSize(16);
    }

    @Test
    @DisplayName("모르는 type — Grafana 는 열어 두고, 이벤트는 한 번 소식이다. 둘 다 개발자 · 확인")
    void unknown() {
        assertThat(AdminAlertRules.known("NEW_THING")).isFalse();
        assertThat(AdminAlertRules.of("NEW_THING", Source.GRAFANA).lifecycle()).isEqualTo(Lifecycle.ONGOING);
        assertThat(AdminAlertRules.of("NEW_THING", Source.EVENT).lifecycle()).isEqualTo(Lifecycle.ONCE);
        assertThat(AdminAlertRules.of("NEW_THING", Source.EVENT).audiences()).containsExactly(Audience.DEVELOPER);
        assertThat(AdminAlertRules.of("NEW_THING", Source.EVENT).severity()).isEqualTo(Severity.CHECK);
    }

    @Test
    @DisplayName("2배 이상이면 바로 올린다")
    void doubles() {
        assertThat(raise("10", "19", 1)).isFalse();
        assertThat(raise("10", "20", 1)).isTrue();
    }

    @Test
    @DisplayName("100 단위를 넘으면 바로 올린다")
    void hundreds() {
        assertThat(raise("150", "199", 1)).isFalse();
        assertThat(raise("150", "200", 1)).isTrue();
        assertThat(raise("860", "905", 1)).isTrue();
    }

    @Test
    @DisplayName("5분이 지났으면 늘기만 해도 올리지만, 그대로거나 줄면 5분이 지나도 올리지 않는다")
    void fiveMinutes() {
        assertThat(raise("10", "11", 299)).isFalse();
        assertThat(raise("10", "11", 300)).isTrue();
        assertThat(raise("10", "10", 3600)).isFalse();
        assertThat(raise("10", "9", 3600)).isFalse();
    }

    @Test
    @DisplayName("값이 없으면 올리지 않고, 처음 값이 없다가 0 보다 큰 값이 오면 올린다")
    void missing() {
        assertThat(raise("10", null, 3600)).isFalse();
        assertThat(raise(null, "1", 1)).isTrue();
        assertThat(raise(null, "0", 1)).isFalse();
    }

    private static boolean raise(String raised, String current, long secondsLater) {
        return AdminAlertRules.shouldRaise(raised == null ? null : new BigDecimal(raised), current == null ? null : new BigDecimal(current),
                RAISED, RAISED.plusSeconds(secondsLater));
    }
}
