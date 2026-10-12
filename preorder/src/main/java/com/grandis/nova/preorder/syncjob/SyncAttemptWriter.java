package com.grandis.nova.preorder.syncjob;

import com.grandis.nova.common.UuidBinary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 시도 기록 쓰기. 결과 이벤트 하나가 한 줄이다 — 같은 이벤트를 다시 받으면 남기지 않는다.
 * 번호는 작업 안에서 이어 매긴다. 호출하는 쪽이 예약 행을 잠가 한 작업의 기록이 겹치지 않게 한다.
 */
@Component
class SyncAttemptWriter {

    static final int ERROR_CODE_LENGTH = 100;
    static final int ERROR_MESSAGE_LENGTH = 500;

    private static final String EXISTS = "SELECT COUNT(*) FROM preorder_sync_attempts WHERE event_id = ?";

    private static final String INSERT = """
            INSERT INTO preorder_sync_attempts (sync_job_id, attempt_number, generation, generation_attempt, actor,
                                                result, http_status, error_code, error_message, started_at,
                                                finished_at, event_id)
            SELECT ?, COALESCE(MAX(attempt_number), 0) + 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
              FROM preorder_sync_attempts
             WHERE sync_job_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    SyncAttemptWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** @return 새로 남겼으면 true, 이미 남긴 이벤트면 false */
    boolean write(UUID syncJobId, String eventId, NewSyncAttempt attempt) {
        Integer recorded = jdbcTemplate.queryForObject(EXISTS, Integer.class, eventId);
        if (recorded != null && recorded > 0) {
            return false;
        }
        byte[] jobId = UuidBinary.toBytes(syncJobId);
        jdbcTemplate.update(INSERT, jobId, attempt.generation(), attempt.attempt(), attempt.actor(), attempt.result(),
                attempt.httpStatus(), cut(attempt.errorCode(), ERROR_CODE_LENGTH),
                cut(attempt.errorMessage(), ERROR_MESSAGE_LENGTH), utc(attempt.startedAt()),
                utc(attempt.finishedAt()), eventId, jobId);
        return true;
    }

    /** 칸 길이(글자 수)에 맞춘다. 외부가 준 값이라 길이를 믿지 않는다. */
    private static String cut(String value, int length) {
        if (value == null || value.codePointCount(0, value.length()) <= length) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, length));
    }

    /** DB 는 UTC 벽시계 시각을 담는다. */
    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }
}
