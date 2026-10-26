package com.grandis.nova.member.adminalert;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.member.adminalert.AdminAlertRules.Audience;
import com.grandis.nova.member.adminalert.AdminAlertRules.Severity;
import com.grandis.nova.member.adminalert.AdminAlertRules.Status;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Collection;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 관리자 알림 조회 · 읽음 표시. 모두 짧은 문장 하나라 트랜잭션 없이 쓴다.
 * 목록은 ix_admin_alert_list(탭을 고르면) · ix_admin_alert_raised(안 고르면), 안 읽은 수는 ix_admin_alert_unread, 실시간 연결의 꼬리는
 * ix_admin_alert_changed 를 탄다.
 */
@Component
class AdminAlertQueries {

    private static final String COLUMNS = """
            SELECT id, type, audience, severity, status, source, target_kind, target_id, target_name, metrics, first_occurred_at,
                   last_updated_at, raised_at, resolved_at, read_at, changed_at, version FROM admin_alerts""";
    private static final String CHANGED_FROM = COLUMNS + " WHERE changed_at >= :at ORDER BY changed_at, id LIMIT :limit";
    private static final String CHANGED_AFTER = COLUMNS
            + " WHERE (changed_at > :at OR (changed_at = :at AND id > :id)) ORDER BY changed_at, id LIMIT :limit";
    private static final String UNREAD = """
            SELECT audience, severity, COUNT(*) FROM admin_alerts WHERE read_at IS NULL GROUP BY audience, severity""";

    private final NamedParameterJdbcTemplate jdbc;

    AdminAlertQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 목록 조건. null 이면 거르지 않는다. */
    record Filter(Audience audience, Status status, List<Severity> severities, boolean unreadOnly) {
    }

    /** 표 한 행. metrics 는 JSON 문자열 그대로다. version 은 바뀔 때마다 1씩 오른다. */
    record View(UUID id, String type, Audience audience, Severity severity, Status status, AdminAlertRules.Source source,
                String targetKind, String targetId, String targetName, String metrics, Instant firstOccurredAt,
                Instant lastUpdatedAt, Instant raisedAt, Instant resolvedAt, Instant readAt, Instant changedAt, long version) {
    }

    /** raised_at · id 내림차순. after 가 있으면 그보다 뒤(더 오래된 것)만. */
    List<View> list(Filter filter, AdminAlertCursor after, int limit) {
        StringBuilder sql = new StringBuilder(COLUMNS).append(" WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource("limit", limit);
        if (filter.audience() != null) {
            sql.append(" AND audience = :audience");
            params.addValue("audience", filter.audience().name());
        }
        if (filter.status() != null) {
            sql.append(" AND status = :status");
            params.addValue("status", filter.status().name());
        }
        if (filter.severities() != null && !filter.severities().isEmpty()) {
            sql.append(" AND severity IN (:severities)");
            params.addValue("severities", filter.severities().stream().map(Enum::name).toList());
        }
        if (filter.unreadOnly()) {
            sql.append(" AND read_at IS NULL");
        }
        if (after != null) {
            sql.append(" AND (raised_at < :raisedAt OR (raised_at = :raisedAt AND id < :id))");
            params.addValue("raisedAt", AdminAlertWriter.utc(after.raisedAt())).addValue("id", UuidBinary.toBytes(after.id()));
        }
        sql.append(" ORDER BY raised_at DESC, id DESC LIMIT :limit");
        return jdbc.query(sql.toString(), params, VIEW);
    }

    /** 바뀐 시각 · id 오름차순 한 쪽. after 가 null 이면 at 부터, 아니면 그 행 다음부터. */
    List<View> changed(Instant at, View after, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("limit", limit);
        if (after == null) {
            return jdbc.query(CHANGED_FROM, params.addValue("at", AdminAlertWriter.utc(at)), VIEW);
        }
        return jdbc.query(CHANGED_AFTER, params.addValue("at", AdminAlertWriter.utc(after.changedAt()))
                .addValue("id", UuidBinary.toBytes(after.id())), VIEW);
    }

    /** 받는 사람 → 심각도 → 안 읽은 수. 없는 칸은 0 이다. */
    Map<Audience, Map<Severity, Long>> unreadCounts() {
        Map<Audience, Map<Severity, Long>> counts = new EnumMap<>(Audience.class);
        for (Audience audience : Audience.values()) {
            Map<Severity, Long> bySeverity = new EnumMap<>(Severity.class);
            for (Severity severity : Severity.values()) {
                bySeverity.put(severity, 0L);
            }
            counts.put(audience, bySeverity);
        }
        jdbc.query(UNREAD, rs -> {
            Map<Severity, Long> bySeverity = counts.get(Audience.valueOf(rs.getString(1)));
            if (bySeverity != null) {
                bySeverity.put(Severity.valueOf(rs.getString(2)), rs.getLong(3));
            }
        });
        return counts;
    }

    /** @return 바꾼 행 수(0 또는 1). 이미 읽었으면 0 — 처음 읽은 시각을 덮지 않는다 */
    int markRead(UUID id, Instant now) {
        return jdbc.update("UPDATE admin_alerts SET read_at = :now, changed_at = :now, version = version + 1 WHERE id = :id AND read_at IS NULL",
                new MapSqlParameterSource("now", AdminAlertWriter.utc(now)).addValue("id", UuidBinary.toBytes(id)));
    }

    /** 이 트랜잭션이 바꾼 행만 changed_at 을 지금으로 다시 찍는다(AdminAlertWriter.restamp). */
    void restamp(Collection<UUID> ids, Clock clock) {
        AdminAlertWriter.restamp(jdbc.getJdbcOperations(), ids, clock);
    }

    boolean exists(UUID id) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM admin_alerts WHERE id = :id",
                new MapSqlParameterSource("id", UuidBinary.toBytes(id)), Long.class);
        return count != null && count > 0;
    }

    /**
     * 안 읽은 행을 잠금 읽기로 골라 그 id 만 읽음으로 바꾼다 — 바꾼 행을 정확히 알아야 다시 찍기가 남의 행을 건드리지 않는다. 바뀐 행이 많으면 꼬리 읽기가
     * 행마다 보내지 않고 resync 하나로 알린다(AdminAlertTail).
     *
     * @param audience null 이면 둘 다
     * @return 읽음으로 바꾼 행
     */
    List<UUID> markAllRead(Audience audience, Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String select = "SELECT id FROM admin_alerts WHERE read_at IS NULL";
        if (audience != null) {
            select += " AND audience = :audience";
            params.addValue("audience", audience.name());
        }
        List<UUID> ids = jdbc.query(select + " FOR UPDATE", params, (rs, i) -> UuidBinary.fromBytes(rs.getBytes(1)));
        for (int from = 0; from < ids.size(); from += AdminAlertWriter.ID_CHUNK) {
            List<byte[]> chunk = ids.subList(from, Math.min(from + AdminAlertWriter.ID_CHUNK, ids.size())).stream()
                    .map(UuidBinary::toBytes).toList();
            jdbc.update("UPDATE admin_alerts SET read_at = :now, changed_at = :now, version = version + 1 WHERE id IN (:ids)",
                    new MapSqlParameterSource("now", AdminAlertWriter.utc(now)).addValue("ids", chunk));
        }
        return ids;
    }

    private static final RowMapper<View> VIEW = (rs, row) -> new View(UuidBinary.fromBytes(rs.getBytes("id")), rs.getString("type"),
            Audience.valueOf(rs.getString("audience")), Severity.valueOf(rs.getString("severity")),
            Status.valueOf(rs.getString("status")), AdminAlertRules.Source.valueOf(rs.getString("source")),
            rs.getString("target_kind"), rs.getString("target_id"), rs.getString("target_name"), rs.getString("metrics"),
            AdminAlertWriter.instant(rs, 11), AdminAlertWriter.instant(rs, 12), AdminAlertWriter.instant(rs, 13),
            AdminAlertWriter.instant(rs, 14), AdminAlertWriter.instant(rs, 15), AdminAlertWriter.instant(rs, 16), rs.getLong("version"));
}
