package com.grandis.nova.member.adminalert;

import com.grandis.nova.member.adminalert.AdminAlertRejected.Reason;
import com.grandis.nova.member.adminalert.AdminAlertRules.Source;
import com.grandis.nova.member.adminalert.AdminAlertRules.TargetKind;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Grafana 큐(SNS 원문 전달)의 메시지 하나를 풀어 저장한다. 모양은 contracts/admin-alert-grafana.md 다. 메시지 속성은 없다 — 본문만 읽는다.
 *
 * 경보 하나라도 계약에 맞지 않으면 아무것도 저장하지 않고 메시지 전체를 거절한다(규칙 설정 오류라 사람이 고칠 일이다). 인프라가 경보 하나에 메시지
 * 하나를 보내므로(group_by ['...']) 늦어지는 것은 그 경보뿐이다. 사건의 이름(episode)은 startsAt 이다 — Grafana 는 한 사건 동안 바꾸지 않는다.
 * 라벨은 admin_alert_* 셋, 주석은 metric_* 만 읽고 나머지(alertname · severity · job · summary 등)는 무시한다.
 *
 * 값을 알아챈 시각(seenAt)은 SQS 가 메시지를 받은 시각(SentTimestamp)이다 — 처리 시각을 쓰면 백오프로 늦게 다시 받은 옛 메시지의 숫자가 그사이 온 새 숫자를
 * 덮는다. 표의 칸에 담기지 않는 값(주 숫자는 decimal(20,4), 나머지는 JSON 의 double)은 계약 위반이다 — 넣다 실패하면 이유 없이 재수신을 돌다 DLQ 로 간다.
 */
@Component
class GrafanaAlertReceiver {

    static final String VERSION = "1";
    static final String TYPE_LABEL = "admin_alert_type";
    static final String KIND_LABEL = "admin_alert_target_kind";
    static final String ID_LABEL = "admin_alert_target_id";
    static final String METRIC_PREFIX = "metric_";
    /** Grafana 가 firing 경보의 endsAt 에 넣는 빈 시각(Go 의 zero time) */
    static final Instant ZERO_TIME = Instant.parse("0001-01-01T00:00:00Z");
    /** Grafana 가 값이 없을 때 찍는 글자(Go 템플릿의 nil 은 "&lt;nil&gt;"). null 로 담는다 — 음수는 뜻이 있는 값이라 그대로 둔다 */
    static final Set<String> NO_VALUE = Set.of("", "NaN", "+Inf", "-Inf", "Inf", "<nil>");

    private final AdminAlertIntake intake;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    GrafanaAlertReceiver(AdminAlertIntake intake, JsonMapper jsonMapper, Clock clock) {
        this.intake = intake;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /**
     * @param sentAt SQS 가 메시지를 받은 시각. null 이면 지금
     * @throws AdminAlertRejected 풀 수 없거나 계약에 맞지 않는 메시지
     * @throws RuntimeException   저장 중 오류. 다시 받으면 앞서 저장한 경보는 같은 결과로 다시 지난다
     */
    void receive(String body, Instant sentAt) {
        parse(body, sentAt).forEach(intake::accept);
    }

    void receive(String body) {
        receive(body, null);
    }

    List<AdminAlertSignal> parse(String body) {
        return parse(body, null);
    }

    List<AdminAlertSignal> parse(String body, Instant sentAt) {
        JsonNode message;
        try {
            message = jsonMapper.readTree(body);
        } catch (JacksonException e) {
            throw new AdminAlertRejected(Reason.UNREADABLE, "Grafana 메시지를 풀 수 없다", e);
        }
        if (message == null || !message.isObject()) {
            throw new AdminAlertRejected(Reason.UNREADABLE, "Grafana 메시지가 객체가 아니다");
        }
        JsonNode alerts = message.get("alerts");
        if (!VERSION.equals(AdminAlertEventReceiver.text(message.get("version"))) || alerts == null || !alerts.isArray()
                || alerts.isEmpty()) {
            throw new AdminAlertRejected(Reason.INVALID_MESSAGE, "version 이 " + VERSION + " 이 아니거나 alerts 가 비었다");
        }
        Instant now = clock.instant();
        Instant seenAt = sentAt == null ? now : sentAt;
        List<AdminAlertSignal> signals = new ArrayList<>();
        for (JsonNode alert : alerts) {
            signals.add(signal(alert, seenAt));
        }
        return signals;
    }

    private AdminAlertSignal signal(JsonNode alert, Instant seenAt) {
        if (alert == null || !alert.isObject()) {
            throw invalid("alerts 의 원소가 객체가 아니다: " + (alert == null ? null : alert.getNodeType()));
        }
        String status = AdminAlertEventReceiver.text(alert.get("status"));
        if (!"firing".equals(status) && !"resolved".equals(status)) {
            throw invalid("status 는 firing · resolved 다: " + status);
        }
        JsonNode labels = alert.get("labels");
        String type = labels == null ? null : AdminAlertEventReceiver.text(labels.get(TYPE_LABEL));
        if (type == null || type.length() > AdminAlertEventReceiver.TYPE_LENGTH) {
            throw invalid(TYPE_LABEL + " 라벨이 없거나 " + AdminAlertEventReceiver.TYPE_LENGTH + "자를 넘는다");
        }
        TargetKind kind = kind(AdminAlertEventReceiver.text(labels.get(KIND_LABEL)), type);
        String targetId = "";
        if (kind != TargetKind.NONE) {
            targetId = AdminAlertEventReceiver.text(labels.get(ID_LABEL));
            if (targetId == null || targetId.length() > AdminAlertEventReceiver.TARGET_ID_LENGTH) {
                throw invalid(type + " 의 " + ID_LABEL + " 라벨이 없거나 너무 길다(대상 " + kind + ")");
            }
        }
        Instant startsAt = time(alert.get("startsAt"), "startsAt", type);
        if (startsAt == null) {
            throw invalid(type + " 의 startsAt 이 없다");
        }
        boolean resolved = "resolved".equals(status);
        Instant endsAt = time(alert.get("endsAt"), "endsAt", type);
        if (resolved && endsAt == null) {
            throw invalid(type + " 의 resolved 에 endsAt 이 없다 — 해소 시각을 지어내지 않는다");
        }
        Instant resolvedAt = resolved ? endsAt : null;
        ObjectNode metrics = metrics(alert.get("annotations"));
        if (!AdminAlertMetrics.storable(metrics)) {
            throw invalid(type + " 의 metric_* 에 JSON 칸이 담을 수 없는 숫자(double 범위 밖)가 있다");
        }
        String primary = AdminAlertRules.of(type, Source.GRAFANA).primaryMetric();
        JsonNode value = primary == null ? null : metrics.get(primary);
        if (value != null && value.isNumber() && !AdminAlertMetrics.fitsPrimary(value.decimalValue())) {
            throw invalid(type + " 의 주 숫자 metric_" + primary + " 가 담을 수 있는 범위(±10^16)를 넘는다: " + value);
        }
        return new AdminAlertSignal(Source.GRAFANA, type, kind, targetId, startsAt.toString(), resolved, startsAt, resolvedAt, seenAt,
                metrics, null);
    }

    /** 라벨이 없으면 NONE(대상 없는 경보). */
    private static TargetKind kind(String label, String type) {
        if (label == null) {
            return TargetKind.NONE;
        }
        try {
            return TargetKind.valueOf(label);
        } catch (IllegalArgumentException e) {
            throw invalid(type + " 의 " + KIND_LABEL + " 는 NONE · PRODUCT · DRAW · SERVICE 다: " + label);
        }
    }

    /** @return 비었거나 Go 의 zero time 이면 null */
    private static Instant time(JsonNode node, String name, String type) {
        String value = AdminAlertEventReceiver.text(node);
        if (value == null) {
            return null;
        }
        Instant at;
        try {
            at = Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw invalid(type + " 의 " + name + " 를 풀 수 없다: " + value);
        }
        if (!at.isAfter(ZERO_TIME)) {
            return null;
        }
        if (at.isBefore(AdminAlertEventReceiver.EARLIEST) || !at.isBefore(AdminAlertEventReceiver.LIMIT)) {
            throw invalid(type + " 의 " + name + " 가 담을 수 없는 시각이다: " + value);
        }
        return at;
    }

    /** metric_&lt;이름&gt; 주석만 metrics 의 &lt;이름&gt; 칸으로 옮긴다. 숫자로 풀리면 숫자, 값이 없음을 뜻하는 글자면 null, 그 밖은 문자열이다. */
    private ObjectNode metrics(JsonNode annotations) {
        ObjectNode metrics = jsonMapper.createObjectNode();
        if (annotations == null || !annotations.isObject()) {
            return metrics;
        }
        for (Map.Entry<String, JsonNode> entry : annotations.properties()) {
            String name = entry.getKey();
            if (!name.startsWith(METRIC_PREFIX) || name.length() == METRIC_PREFIX.length()) {
                continue;
            }
            String field = name.substring(METRIC_PREFIX.length());
            String value = entry.getValue().isString() ? entry.getValue().asString().trim() : entry.getValue().toString();
            if (NO_VALUE.contains(value)) {
                metrics.putNull(field);
                continue;
            }
            try {
                metrics.put(field, new BigDecimal(value));
            } catch (NumberFormatException e) {
                metrics.put(field, value);
            }
        }
        return metrics;
    }

    private static AdminAlertRejected invalid(String why) {
        return new AdminAlertRejected(Reason.INVALID_ALERT, "Grafana 경보가 계약에 맞지 않는다: " + why);
    }
}
