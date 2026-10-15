package com.grandis.nova.preorder.campaign.domain;

import com.grandis.nova.common.UuidBinary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 오픈 알림 발송 진행(preorder_open_alert_dispatches). 회차당 한 행이고, 보낸 마지막 신청자를 커서로 둔다.
 * 묶음 하나를 보내는 트랜잭션이 이 행을 잠근다 — 회차 행은 잠그지 않는다.
 */
@Component
public class OpenAlertDispatchStore {

    /**
     * 그냥 넣는다. 오픈 표시는 회차당 한 번이라(오픈 뒤 일정 변경 금지) 행이 이미 있으면 버그다 — 키 충돌로 표시까지 롤백한다.
     * 덮어쓰면 진행 중인 커서가 처음으로 돌아가 보낸 묶음이 다시 나간다.
     */
    private static final String START = """
            INSERT INTO preorder_open_alert_dispatches (product_id, last_customer_id, created_at, completed_at)
            VALUES (?, NULL, ?, NULL)
            """;

    private static final String LOCK = """
            SELECT last_customer_id, completed_at FROM preorder_open_alert_dispatches WHERE product_id = ? FOR UPDATE
            """;

    private static final String ADVANCE = """
            UPDATE preorder_open_alert_dispatches SET last_customer_id = ?, completed_at = ? WHERE product_id = ?
            """;

    private static final String PENDING = """
            SELECT product_id FROM preorder_open_alert_dispatches
             WHERE completed_at IS NULL
             ORDER BY created_at, product_id
             LIMIT ?
            """;

    private static final String BACKLOG = """
            SELECT COUNT(*), MIN(created_at) FROM preorder_open_alert_dispatches WHERE completed_at IS NULL
            """;

    private final JdbcTemplate jdbcTemplate;

    OpenAlertDispatchStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 오픈 표시와 같은 트랜잭션에서 부른다. */
    public void start(UUID productId, Instant now) {
        jdbcTemplate.update(START, UuidBinary.toBytes(productId), utc(now));
    }

    /** 진행 행을 트랜잭션 끝까지 잠근다. 없으면 빈 값. */
    public Optional<Cursor> lock(UUID productId) {
        return jdbcTemplate.query(LOCK, (rs, rowNum) -> {
            byte[] last = rs.getBytes("last_customer_id");
            return new Cursor(last == null ? null : UuidBinary.fromBytes(last), rs.getObject("completed_at") != null);
        }, (Object) UuidBinary.toBytes(productId)).stream().findFirst();
    }

    /**
     * 커서를 옮긴다. 잠근 트랜잭션에서, 그 묶음의 아웃박스 행과 함께 부른다.
     *
     * @param completedAt 다 보냈으면 그 시각, 남았으면 null
     */
    public void advance(UUID productId, UUID lastCustomerId, Instant completedAt) {
        jdbcTemplate.update(ADVANCE, lastCustomerId == null ? null : UuidBinary.toBytes(lastCustomerId),
                completedAt == null ? null : utc(completedAt), UuidBinary.toBytes(productId));
    }

    /** 끝나지 않은 발송. 오래된 것부터. 잠그지 않는다. */
    public List<UUID> pending(int limit) {
        return jdbcTemplate.query(PENDING, (rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes(1)), limit);
    }

    /** 끝나지 않은 발송 수와 가장 오래된 것의 시작 시각(없으면 null). 잠그지 않는다. */
    public Backlog backlog() {
        return jdbcTemplate.queryForObject(BACKLOG, (rs, rowNum) -> {
            LocalDateTime oldest = rs.getObject(2, LocalDateTime.class);
            return new Backlog(rs.getLong(1), oldest == null ? null : oldest.toInstant(ZoneOffset.UTC));
        });
    }

    /** DB 는 UTC 벽시계 시각을 담는다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    /** @param lastCustomerId 보낸 마지막 신청자. null 은 아직 한 묶음도 보내기 전 */
    public record Cursor(UUID lastCustomerId, boolean completed) {
    }

    public record Backlog(long count, Instant oldestCreatedAt) {
    }
}
