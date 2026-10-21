package com.grandis.nova.preorder.deadletter.domain;

import com.grandis.nova.preorder.event.InboundEventType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 되돌리기를 기다리는(OPEN · 오래된 REDRIVING) 행을 종류 · 사유별로 센다. 종류는 DB 에서 받는 종류 · OTHER · UNKNOWN 으로 접어 묶는다 —
 * 보낸 쪽 문자열로 묶으면 낯선 종류가 늘수록 결과 행이 는다. 받는 종류 목록은 enum 이름이라 SQL 에 그대로 넣는다.
 */
@Component
public class DeadLetterWaitingQuery {

    /** 받지 않는 종류. */
    public static final String OTHER = "OTHER";
    /** 읽지 못한 메시지(종류 없음). */
    public static final String UNKNOWN = "UNKNOWN";

    private static final String KIND = "CASE WHEN event_type IS NULL THEN '" + UNKNOWN + "' WHEN event_type IN ("
            + Arrays.stream(InboundEventType.values()).map(type -> "'" + type.name() + "'").collect(Collectors.joining(", "))
            + ") THEN event_type ELSE '" + OTHER + "' END";
    private static final String SQL = "SELECT " + KIND + " AS event_kind, failure_reason, COUNT(*), MIN(created_at)"
            + " FROM preorder_dead_letter_events"
            + " WHERE status = :open OR (status = :redriving AND redrive_started_at < :staleBefore)"
            + " GROUP BY event_kind, failure_reason";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    DeadLetterWaitingQuery(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 합계 · 가장 오래된 것도 이 한 문장에서 낸다. */
    public List<WaitingGroup> countByKind(Instant staleBefore) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("open", DeadLetterStatus.OPEN.name())
                .addValue("redriving", DeadLetterStatus.REDRIVING.name())
                .addValue("staleBefore", LocalDateTime.ofInstant(staleBefore.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC));
        return jdbcTemplate.query(SQL, params, (rs, rowNum) -> new WaitingGroup(rs.getString(1),
                FailureReason.valueOf(rs.getString(2)), rs.getLong(3),
                rs.getObject(4, LocalDateTime.class).toInstant(ZoneOffset.UTC)));
    }

    /** @param eventKind 받는 종류 이름 · {@link #OTHER} · {@link #UNKNOWN} */
    public record WaitingGroup(String eventKind, FailureReason failureReason, long count, Instant oldestAt) {
    }
}
