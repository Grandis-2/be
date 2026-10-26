package com.grandis.nova.member.adminalert;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** metrics 의 값이 표의 칸에 담기는지. 담기지 않는 값은 넣을 때 DB 오류로 끝나 일시 오류처럼 재수신을 돌다 이유 없이 DLQ 로 간다 — 받을 때 거른다. */
final class AdminAlertMetrics {

    /** raised_value decimal(20,4) 의 정수부 16자리 */
    static final BigDecimal PRIMARY_LIMIT = BigDecimal.TEN.pow(16);
    static final int PRIMARY_SCALE = 4;

    private AdminAlertMetrics() {
    }

    /** MySQL JSON 칸은 숫자를 double 로 담는다 — 그 범위를 넘거나 NaN · 무한대면 false. */
    static boolean storable(ObjectNode metrics) {
        for (JsonNode value : metrics.values()) {
            if (value.isNumber() && !Double.isFinite(value.isBigDecimal() ? value.decimalValue().doubleValue() : value.doubleValue())) {
                return false;
            }
        }
        return true;
    }

    /** 칸의 자리(소수 4자리)로 반올림한 값이 ±10^16 안인가 — 반올림해서 넘는 값도 칸에 담기지 않는다. */
    static boolean fitsPrimary(BigDecimal value) {
        return value.setScale(PRIMARY_SCALE, RoundingMode.HALF_UP).abs().compareTo(PRIMARY_LIMIT) < 0;
    }
}
