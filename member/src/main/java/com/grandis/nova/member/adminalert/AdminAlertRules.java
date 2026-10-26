package com.grandis.nova.member.adminalert;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * type 마다 받는 사람 · 심각도 · 해소 방식 · 주 숫자를 정한다(contracts/admin-alert-hub.md 의 표). 보내는 쪽은 사실만 보내고 이 판단은 member 가 한다.
 * 모르는 type 도 저장한다 — 새 종류를 member 배포 없이 더할 수 있게. 그때는 개발자 · 확인으로 둔다.
 */
final class AdminAlertRules {

    enum Audience { ADMIN, DEVELOPER }

    enum Severity { URGENT, CHECK, INFO }

    enum Status { OPEN, RESOLVED }

    /** ONGOING = 열렸다가 해소된다. ONCE = 한 번 오는 소식이라 넣을 때 해소로 둔다(resolvable=false). */
    enum Lifecycle { ONGOING, ONCE }

    enum TargetKind { NONE, PRODUCT, DRAW, SERVICE }

    enum Source { GRAFANA, EVENT }

    /**
     * @param primaryMetric 재알림을 견줄 숫자(metrics 의 칸). null 이면 다시 올리지 않는다
     * @param escalateAfter 처음 생긴 뒤 이만큼 지나면 URGENT 로 올린다. null 이면 바꾸지 않는다
     */
    record Rule(List<Audience> audiences, Severity severity, Lifecycle lifecycle, String primaryMetric, Duration escalateAfter) {
    }

    /** 마지막으로 올린 뒤 이만큼 지났고 주 숫자가 늘었으면 다시 올린다. */
    static final Duration RERAISE_AFTER = Duration.ofMinutes(5);
    static final BigDecimal STEP = BigDecimal.valueOf(100);

    private static final List<Audience> ADMIN = List.of(Audience.ADMIN);
    private static final List<Audience> DEVELOPER = List.of(Audience.DEVELOPER);
    private static final List<Audience> BOTH = List.of(Audience.ADMIN, Audience.DEVELOPER);

    static final Map<String, Rule> RULES = Map.ofEntries(
            Map.entry("REGISTER_FAILED", ongoing(ADMIN, Severity.URGENT, "deadLetters")),
            Map.entry("EVENT_DLQ_WAITING", ongoing(DEVELOPER, Severity.CHECK, "waiting")),
            Map.entry("QUEUE_BRAKING", ongoing(DEVELOPER, Severity.URGENT, null)),
            Map.entry("ADMISSION_STOPPED", ongoing(BOTH, Severity.URGENT, "waiting")),
            Map.entry("QUEUE_SERVER_UNHEALTHY",
                    new Rule(ADMIN, Severity.CHECK, Lifecycle.ONGOING, null, Duration.ofMinutes(5))),
            Map.entry("EXTERNAL_SYSTEM_DOWN", ongoing(ADMIN, Severity.URGENT, null)),
            Map.entry("SERVICE_INCIDENT", ongoing(BOTH, Severity.URGENT, null)),
            Map.entry("SERVICE_ERROR_SPIKE", ongoing(DEVELOPER, Severity.URGENT, null)),
            Map.entry("QUEUE_LONG_WAIT", ongoing(ADMIN, Severity.CHECK, null)),
            Map.entry("SHIPPING_BACKLOG", ongoing(ADMIN, Severity.INFO, "total")),
            Map.entry("PAYMENT_FAILURE_SPIKE", ongoing(DEVELOPER, Severity.CHECK, null)),
            Map.entry("RECONCILE_MISMATCH", ongoing(BOTH, Severity.CHECK, "count")),
            Map.entry("PREORDER_OPENED", once(Severity.INFO)),
            Map.entry("PREORDER_CLOSED", once(Severity.INFO)),
            Map.entry("DRAW_SETTLED", once(Severity.INFO)),
            // 같은 회차의 DRAW_SETTLED 가 해소한다. 검사마다 다시 오지만 숫자가 없어 다시 올리지 않는다
            Map.entry("DRAW_DELAYED", ongoing(ADMIN, Severity.CHECK, null)));

    /** Grafana 경보는 resolved 가 오므로 열어 두고, 모르는 이벤트는 해소해 줄 쪽이 없어 한 번 소식으로 둔다. */
    private static final Rule UNKNOWN_GRAFANA = ongoing(DEVELOPER, Severity.CHECK, null);
    private static final Rule UNKNOWN_EVENT = new Rule(DEVELOPER, Severity.CHECK, Lifecycle.ONCE, null, null);

    private AdminAlertRules() {
    }

    static Rule of(String type, Source source) {
        Rule rule = RULES.get(type);
        if (rule != null) {
            return rule;
        }
        return source == Source.GRAFANA ? UNKNOWN_GRAFANA : UNKNOWN_EVENT;
    }

    static boolean known(String type) {
        return RULES.containsKey(type);
    }

    /**
     * 열린 알림을 다시 "새 알림"으로 올릴지. 주 숫자가 늘었을 때만 본다 — 마지막으로 올린 값의 2배 이상이거나 100 단위를 넘었으면 바로,
     * 아니면 마지막으로 올린 뒤 5분이 지났을 때.
     */
    static boolean shouldRaise(BigDecimal raisedValue, BigDecimal current, Instant raisedAt, Instant now) {
        if (current == null) {
            return false;
        }
        if (raisedValue == null) {
            return current.signum() > 0;
        }
        if (current.compareTo(raisedValue) <= 0) {
            return false;
        }
        return current.compareTo(raisedValue.multiply(BigDecimal.TWO)) >= 0
                || hundreds(current).compareTo(hundreds(raisedValue)) > 0
                || !raisedAt.plus(RERAISE_AFTER).isAfter(now);
    }

    private static BigDecimal hundreds(BigDecimal value) {
        return value.divide(STEP, 0, RoundingMode.FLOOR);
    }

    private static Rule ongoing(List<Audience> audiences, Severity severity, String primaryMetric) {
        return new Rule(audiences, severity, Lifecycle.ONGOING, primaryMetric, null);
    }

    private static Rule once(Severity severity) {
        return new Rule(ADMIN, severity, Lifecycle.ONCE, null, null);
    }
}
