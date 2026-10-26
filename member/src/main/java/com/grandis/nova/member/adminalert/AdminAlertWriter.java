package com.grandis.nova.member.adminalert;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.member.adminalert.AdminAlertRules.Audience;
import com.grandis.nova.member.adminalert.AdminAlertRules.Lifecycle;
import com.grandis.nova.member.adminalert.AdminAlertRules.Rule;
import com.grandis.nova.member.adminalert.AdminAlertRules.Severity;
import com.grandis.nova.member.adminalert.AdminAlertRules.Source;
import com.grandis.nova.member.adminalert.AdminAlertRules.Status;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 사건 하나를 받는 사람마다 한 행에 반영한다(contracts/admin-alert-hub.md 의 묶음 · 재알림 · 해소 규칙). 한 사건 = 한 트랜잭션이다.
 *
 * 행은 유니크 키(type · audience · 대상 · episode)로 찾아 잠근 뒤 판정한다. 없으면 넣는다 — 두 소비자가 같은 사건을 동시에 넣으면 진 쪽이
 * DuplicateKeyException 으로 끝나고 일시 오류로 다시 받는다(그때는 행이 있어 갱신으로 지난다). INSERT IGNORE 는 쓰지 않는다(회원 알림과 같은 까닭).
 *
 * 순서가 뒤바뀐 메시지는 이렇게 거른다.
 * - 이미 해소된 사건에 늦게 온 firing · 중복 소식: 그대로 둔다.
 * - 같은 묶음에 더 늦게 시작한 사건이 이미 있는 Grafana 경보: 넣지 않는다. 새 사건을 넣을 때는 상태(firing · resolved)와 상관없이 같은 묶음의
 *   더 이른 열린 사건만 닫는다(해소 메시지를 잃어도 열린 알림이 둘이 되지 않게). 넣은 뒤 더 늦은 사건을 잠금 읽기로 다시 보고, 있으면 방금 넣은 행을
 *   지운다 — 오래된 사건의 확인과 넣기 사이에 최신 사건이 들어와도 최신 사건이 열린 채 남는다.
 * - 이미 반영한 시각(last_updated_at) 이하의 소식: 같은 메시지를 다시 받은 것이라 값 · 재알림 판정을 건드리지 않는다.
 * - 같은 회차의 DRAW_SETTLED 가 이미 있는 DRAW_DELAYED: 넣지 않는다(order 는 반영을 커밋한 뒤 DELAYED 를 적지 않는다).
 *
 * 해소 메시지의 빈 값(null)은 마지막 firing 의 숫자를 지우지 않는다 — 해소 문구에 그 숫자가 들어간다.
 *
 * 격리 수준은 READ COMMITTED 여야 한다(TransactionIsolationVerifier 가 기동 때 확인한다). 없는 행을 잠금 읽기로 확인한 뒤 넣는 절차가 REPEATABLE READ
 * 에서는 갭 잠금으로 교착한다. 그 대신 RC 의 잠금 없는 읽기는 다른 트랜잭션의 미커밋 행을 보지 못한다 — DRAW_DELAYED 와 DRAW_SETTLED 가 동시에 오면
 * SETTLED 의 해소가 아직 없는 DELAYED 를 지나치고, DELAYED 의 확인도 미커밋 SETTLED 를 지나쳐 DELAYED 가 열린 채 남을 수 있다. 그래서 DELAYED 를
 * 넣은 뒤 SETTLED 를 잠금 읽기(FOR SHARE)로 다시 본다(같은 묶음의 Grafana 사건끼리도 같은 방식이다) — SETTLED 가 커밋 전이면 그 커밋을 기다렸다가 보고, 있으면 그 자리에서 해소한다. 둘 다 서로의 행을
 * 먼저 넣은 드문 순서에서는 교착(1213)으로 한쪽이 되돌려지고, 그 쪽은 일시 오류로 다시 받아 끝난다.
 *
 * changed_at(실시간 연결이 바뀐 행을 찾는 시각)은 커밋 직전에 이 트랜잭션이 바꾼 행만 다시 찍는다({@link #restamp}). 처음 잡은 시각을 그대로 두면
 * 잠금 대기가 꼬리 읽기의 겹침(10초)보다 길 때 커밋된 행이 이미 창 밖이라 실시간으로 나가지 않는다. 여러 행을 바꾸는 문장(앞 사건 닫기 · DRAW_DELAYED
 * 해소)은 대상 id 를 잠금 읽기로 먼저 골라 그 id 만 바꾼다 — 바꾼 행을 정확히 알아야 남의 행을 다시 찍지(잠그지) 않는다.
 * first_occurred_at · raised_at · resolved_at 같은 사건 시각은 그대로다.
 */
@Component
class AdminAlertWriter {

    /** 사건 하나가 바꾼 것. 받는 사람이 둘이면 더 큰 쪽(뒤쪽)을 돌려준다. */
    enum Outcome { IGNORED, UPDATED, CREATED, RAISED, RESOLVED }

    private static final String LOCK = """
            SELECT id, status, severity, first_occurred_at, last_updated_at, raised_at, raised_value, metrics FROM admin_alerts
             WHERE type = ? AND audience = ? AND target_kind = ? AND target_id = ? AND episode = ? FOR UPDATE""";
    private static final String EXISTS = """
            SELECT COUNT(*) FROM admin_alerts
             WHERE type = ? AND audience = ? AND target_kind = ? AND target_id = ? AND episode = ?""";
    private static final String LATER_EPISODE = """
            SELECT COUNT(*) FROM admin_alerts
             WHERE type = ? AND target_kind = ? AND target_id = ? AND audience = ? AND first_occurred_at > ?""";
    private static final String LATER_EPISODE_LOCKED = LATER_EPISODE + " FOR SHARE";
    private static final String DELETE_OWN = "DELETE FROM admin_alerts WHERE id = ?";
    /** 다시 찍기 · 여러 행 바꾸기의 IN 목록 한 묶음 */
    static final int ID_CHUNK = 500;
    private static final String RESTAMP = "UPDATE admin_alerts SET changed_at = GREATEST(changed_at, ?) WHERE id IN (%s)";
    private static final String SETTLED = """
            SELECT MIN(first_occurred_at) FROM admin_alerts WHERE type = 'DRAW_SETTLED' AND target_kind = 'DRAW' AND target_id = ?""";
    private static final String SETTLED_LOCKED = SETTLED + " FOR SHARE";
    private static final String INSERT = """
            INSERT INTO admin_alerts (id, type, audience, target_kind, target_id, target_name, episode, source, severity, status,
                                      metrics, first_occurred_at, last_updated_at, raised_at, raised_value, resolved_at, read_at,
                                      changed_at, version, last_event_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, 0, ?, ?)""";
    private static final String EARLIER_OPEN = """
            SELECT id FROM admin_alerts
             WHERE type = ? AND audience = ? AND target_kind = ? AND target_id = ? AND status = 'OPEN' AND id <> ?
               AND first_occurred_at < ? FOR UPDATE""";
    private static final String CLOSE = """
            UPDATE admin_alerts SET status = 'RESOLVED', resolved_at = ?, changed_at = ?, version = version + 1
             WHERE id = ? AND status = 'OPEN'""";
    private static final String UPDATE = """
            UPDATE admin_alerts SET metrics = ?, last_updated_at = ?, severity = ?, last_event_id = COALESCE(?, last_event_id),
                                    changed_at = ?, version = version + 1
             WHERE id = ?""";
    private static final String RAISE = """
            UPDATE admin_alerts SET metrics = ?, last_updated_at = ?, severity = ?, last_event_id = COALESCE(?, last_event_id),
                                    raised_at = ?, raised_value = ?, read_at = NULL, changed_at = ?, version = version + 1
             WHERE id = ?""";
    private static final String RESOLVE = """
            UPDATE admin_alerts SET status = 'RESOLVED', resolved_at = ?, metrics = ?, last_updated_at = ?,
                                    raised_at = ?, read_at = NULL, changed_at = ?, version = version + 1
             WHERE id = ?""";
    private static final String OPEN_DELAYED = """
            SELECT id FROM admin_alerts WHERE type = 'DRAW_DELAYED' AND target_kind = 'DRAW' AND target_id = ? AND status = 'OPEN'
               FOR UPDATE""";
    private static final String RESOLVE_DELAYED = """
            UPDATE admin_alerts SET status = 'RESOLVED', resolved_at = ?, last_updated_at = ?, raised_at = ?, read_at = NULL,
                                    changed_at = ?, version = version + 1
             WHERE id = ? AND status = 'OPEN'""";

    private final JdbcTemplate jdbc;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    AdminAlertWriter(JdbcTemplate jdbc, JsonMapper jsonMapper, Clock clock) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** 잠긴 행 하나에서 판정에 쓰는 칸. */
    private record Current(UUID id, Status status, Severity severity, Instant firstOccurredAt, Instant lastUpdatedAt,
                           Instant raisedAt, BigDecimal raisedValue, String metrics) {
    }

    /** 아직 저장하지 않은 사건인가(첫 받는 사람 기준, 잠그지 않는다). 이름을 받을지 가른다. */
    boolean isNew(AdminAlertSignal signal) {
        Audience first = AdminAlertRules.of(signal.type(), signal.source()).audiences().getFirst();
        Long count = jdbc.queryForObject(EXISTS, Long.class, signal.type(), first.name(), signal.targetKind().name(),
                signal.targetId(), signal.episode());
        return count == null || count == 0;
    }

    /**
     * @param targetName 새 행에 넣을 대상 이름. 이미 있는 행에는 쓰지 않는다
     * @throws org.springframework.dao.DuplicateKeyException 읽은 뒤 다른 소비자가 먼저 넣었다 — 다시 받으면 된다
     */
    @Transactional
    Outcome apply(AdminAlertSignal signal, String targetName) {
        Instant now = micros(clock.instant());
        Set<UUID> changed = new LinkedHashSet<>();
        Outcome outcome = applyAt(signal, targetName, now, changed);
        restamp(jdbc, changed, clock);
        return outcome;
    }

    /**
     * 이 트랜잭션이 바꾼 행(ids)의 changed_at 을 지금 시각으로 다시 찍는다. 그 행들은 이미 이 트랜잭션이 잠갔으므로 이 문장은 기다리지 않고, 다른 행은
     * 건드리지 않는다. 시계가 뒤로 가도 changed_at 을 앞으로 되돌리지 않는다(GREATEST).
     */
    static void restamp(JdbcOperations jdbc, Collection<UUID> ids, Clock clock) {
        if (ids.isEmpty()) {
            return;
        }
        LocalDateTime fresh = utc(clock.instant());
        List<UUID> all = List.copyOf(ids);
        for (int from = 0; from < all.size(); from += ID_CHUNK) {
            List<UUID> chunk = all.subList(from, Math.min(from + ID_CHUNK, all.size()));
            Object[] args = new Object[chunk.size() + 1];
            args[0] = fresh;
            for (int i = 0; i < chunk.size(); i++) {
                args[i + 1] = UuidBinary.toBytes(chunk.get(i));
            }
            jdbc.update(RESTAMP.formatted(String.join(", ", Collections.nCopies(chunk.size(), "?"))), args);
        }
    }

    private Outcome applyAt(AdminAlertSignal signal, String targetName, Instant now, Set<UUID> changed) {
        Rule rule = AdminAlertRules.of(signal.type(), signal.source());
        if ("DRAW_DELAYED".equals(signal.type()) && settledAt(SETTLED, signal.targetId()) != null) {
            return Outcome.IGNORED;
        }
        Outcome outcome = Outcome.IGNORED;
        for (Audience audience : rule.audiences()) {
            Outcome one = applyTo(signal, rule, audience, targetName, now, changed);
            outcome = one.compareTo(outcome) > 0 ? one : outcome;
        }
        if ("DRAW_SETTLED".equals(signal.type())) {
            outcome = resolveDelayed(signal.targetId(), signal.occurredAt(), now, outcome, changed);
        } else if ("DRAW_DELAYED".equals(signal.type()) && outcome == Outcome.CREATED) {
            // 같은 회차의 SETTLED 가 이 행을 보지 못했을 수 있다 — 잠금 읽기로 그 커밋을 기다렸다가 보고 해소한다
            Instant settledAt = settledAt(SETTLED_LOCKED, signal.targetId());
            if (settledAt != null) {
                outcome = resolveDelayed(signal.targetId(), settledAt, now, outcome, changed);
            }
        }
        return outcome;
    }

    private Outcome applyTo(AdminAlertSignal signal, Rule rule, Audience audience, String targetName, Instant now, Set<UUID> changed) {
        List<Current> found = jdbc.query(LOCK, AdminAlertWriter::current, signal.type(), audience.name(),
                signal.targetKind().name(), signal.targetId(), signal.episode());
        if (found.isEmpty()) {
            return create(signal, rule, audience, targetName, now, changed);
        }
        Current current = found.getFirst();
        if (rule.lifecycle() == Lifecycle.ONCE || current.status() == Status.RESOLVED) {
            return Outcome.IGNORED;
        }
        if (signal.resolved()) {
            jdbc.update(RESOLVE, utc(signal.resolvedAt()), json(keepLastValues(current.metrics(), signal.metrics())), utc(now),
                    utc(now), utc(now), UuidBinary.toBytes(current.id()));
            changed.add(current.id());
            return Outcome.RESOLVED;
        }
        return update(signal, rule, current, now, changed);
    }

    private Outcome create(AdminAlertSignal signal, Rule rule, Audience audience, String targetName, Instant now, Set<UUID> changed) {
        Instant occurredAt = micros(signal.occurredAt());
        if (signal.source() == Source.GRAFANA && count(LATER_EPISODE, signal.type(), signal.targetKind().name(), signal.targetId(),
                audience.name(), utc(occurredAt)) > 0) {
            return Outcome.IGNORED;
        }
        boolean once = rule.lifecycle() == Lifecycle.ONCE;
        Status status = once || signal.resolved() ? Status.RESOLVED : Status.OPEN;
        Instant resolvedAt = once ? occurredAt : signal.resolved() ? micros(signal.resolvedAt()) : null;
        Instant seenAt = micros(signal.seenAt());
        UUID id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        jdbc.update(INSERT, UuidBinary.toBytes(id), signal.type(), audience.name(), signal.targetKind().name(), signal.targetId(),
                targetName, signal.episode(), signal.source().name(), rule.severity().name(), status.name(),
                json(signal.metrics()), utc(occurredAt), utc(seenAt), utc(now), scaled(primary(rule, signal)), utc(resolvedAt),
                utc(now), signal.eventId(), utc(now));
        changed.add(id);
        if (signal.source() == Source.GRAFANA) {
            // 새 사건은 해소로 먼저 왔어도 같은 묶음의 더 이른 열린 사건을 닫는다. 더 늦은 사건은 건드리지 않는다
            List<UUID> earlier = jdbc.query(EARLIER_OPEN, (rs, i) -> UuidBinary.fromBytes(rs.getBytes(1)), signal.type(), audience.name(),
                    signal.targetKind().name(), signal.targetId(), UuidBinary.toBytes(id), utc(occurredAt));
            for (UUID open : earlier) {
                jdbc.update(CLOSE, utc(occurredAt), utc(now), UuidBinary.toBytes(open));
                changed.add(open);
            }
            // 첫 확인 뒤에 더 늦은 사건이 들어왔으면(커밋 전이면 잠금 읽기로 기다린다) 그것이 최신이다 — 방금 넣은 이 늦은 메시지는 지운다
            if (count(LATER_EPISODE_LOCKED, signal.type(), signal.targetKind().name(), signal.targetId(), audience.name(),
                    utc(occurredAt)) > 0) {
                jdbc.update(DELETE_OWN, (Object) UuidBinary.toBytes(id));
                changed.remove(id);
                return Outcome.IGNORED;
            }
        }
        return status == Status.OPEN ? Outcome.CREATED : Outcome.RESOLVED;
    }

    /** 열린 행의 숫자를 갱신한다. 늦게 온 소식은 숫자를 덮지 않는다. 승격하거나 주 숫자가 늘었으면 다시 올린다. */
    private Outcome update(AdminAlertSignal signal, Rule rule, Current current, Instant now, Set<UUID> changed) {
        Instant seenAt = micros(signal.seenAt());
        // 이미 반영한 시각 이하면 같은 메시지를 다시 받은 것이다 — 값도 재알림 판정도 건드리지 않는다(재수신이 늦어진 것만으로 다시 울리지 않게)
        if (!seenAt.isAfter(current.lastUpdatedAt())) {
            return Outcome.IGNORED;
        }
        boolean escalate = rule.escalateAfter() != null && current.severity() != Severity.URGENT
                && !current.firstOccurredAt().plus(rule.escalateAfter()).isAfter(now);
        Severity severity = escalate ? Severity.URGENT : current.severity();
        BigDecimal value = primary(rule, signal);
        boolean raise = escalate || rule.primaryMetric() != null
                && AdminAlertRules.shouldRaise(current.raisedValue(), value, current.raisedAt(), now);
        byte[] id = UuidBinary.toBytes(current.id());
        changed.add(current.id());
        if (raise) {
            jdbc.update(RAISE, json(signal.metrics()), utc(seenAt), severity.name(), signal.eventId(), utc(now),
                    scaled(value == null ? current.raisedValue() : value), utc(now), id);
            return Outcome.RAISED;
        }
        jdbc.update(UPDATE, json(signal.metrics()), utc(seenAt), severity.name(), signal.eventId(), utc(now), id);
        return Outcome.UPDATED;
    }

    private Outcome resolveDelayed(String drawId, Instant settledAt, Instant now, Outcome outcome, Set<UUID> changed) {
        List<UUID> open = jdbc.query(OPEN_DELAYED, (rs, i) -> UuidBinary.fromBytes(rs.getBytes(1)), drawId);
        int resolved = 0;
        for (UUID id : open) {
            resolved += jdbc.update(RESOLVE_DELAYED, utc(settledAt), utc(now), utc(now), utc(now), UuidBinary.toBytes(id));
            changed.add(id);
        }
        return resolved > 0 && outcome.compareTo(Outcome.RESOLVED) < 0 ? Outcome.RESOLVED : outcome;
    }

    /** 같은 회차의 DRAW_SETTLED 가 반영된 시각. 없으면 null. */
    private Instant settledAt(String sql, String drawId) {
        LocalDateTime at = jdbc.queryForObject(sql, LocalDateTime.class, drawId);
        return at == null ? null : at.toInstant(ZoneOffset.UTC);
    }

    /** 새 값이 null 인 칸은 앞의 값을 둔다. */
    private ObjectNode keepLastValues(String previous, ObjectNode incoming) {
        ObjectNode merged = (ObjectNode) jsonMapper.readTree(previous);
        incoming.properties().forEach(entry -> {
            if (!entry.getValue().isNull() || !merged.has(entry.getKey())) {
                merged.set(entry.getKey(), entry.getValue());
            }
        });
        return merged;
    }

    private long count(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }

    private static BigDecimal primary(Rule rule, AdminAlertSignal signal) {
        if (rule.primaryMetric() == null) {
            return null;
        }
        JsonNode value = signal.metrics().get(rule.primaryMetric());
        return value != null && value.isNumber() ? value.decimalValue() : null;
    }

    private String json(JsonNode node) {
        return jsonMapper.writeValueAsString(node);
    }

    private static Current current(ResultSet rs, int row) throws SQLException {
        return new Current(UuidBinary.fromBytes(rs.getBytes(1)), Status.valueOf(rs.getString(2)), Severity.valueOf(rs.getString(3)),
                instant(rs, 4), instant(rs, 5), instant(rs, 6), rs.getBigDecimal(7), rs.getString(8));
    }

    /** raised_value 는 decimal(20,4) 다 — 칸의 자리로 먼저 맞춰 DB 의 반올림에 맡기지 않는다. */
    private static BigDecimal scaled(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    static Instant instant(ResultSet rs, int column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    static Instant micros(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

    /** datetime(6) 은 UTC 벽시계로 담는다(hibernate.jdbc.time_zone=UTC 와 같은 값). */
    static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }
}
