package com.grandis.nova.preorder.campaign.domain;

import com.grandis.nova.common.UuidBinary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * 회차 마감을 알렸다는 표시(preorder_campaign_close_notices). 마감 하나(상품 · 그 마감 시각)가 한 행이고, 회차 행에는 표시하지 않는다 —
 * 회차 행은 접수가 줄 서는 행이다. 다시 열면 마감 시각이 바뀌어 다시 닫힐 때 한 번 더 알린다.
 */
@Component
public class CloseNoticeStore {

    private static final String MARK = """
            INSERT IGNORE INTO preorder_campaign_close_notices (product_id, closes_at, created_at) VALUES (?, ?, ?)
            """;

    /** ix_campaign_closes 로 (since, now] 에 닫힌 회차만 읽고 표시가 있는 것을 뺀다. */
    private static final String PENDING = """
            SELECT c.product_id, c.closes_at FROM preorder_campaigns c
             WHERE c.closes_at > ? AND c.closes_at <= ?
               AND NOT EXISTS (SELECT 1 FROM preorder_campaign_close_notices n
                                WHERE n.product_id = c.product_id AND n.closes_at = c.closes_at)
             ORDER BY c.closes_at, c.product_id
             LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    CloseNoticeStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 이번에 표시했으면 true, 이미 있으면(다른 인스턴스가 알림) false. 알림을 적는 트랜잭션에서 부른다. */
    public boolean mark(UUID productId, Instant closesAt, Instant now) {
        return jdbcTemplate.update(MARK, UuidBinary.toBytes(productId), utc(closesAt), utc(now)) == 1;
    }

    /** 마감 시각이 (since, upTo] 이고 아직 알리지 않은 마감. 마감이 이른 순. 잠그지 않는다. */
    public List<Closed> findPending(Instant since, Instant upTo, int limit) {
        return jdbcTemplate.query(PENDING, (rs, rowNum) -> new Closed(UuidBinary.fromBytes(rs.getBytes(1)),
                rs.getObject(2, LocalDateTime.class).toInstant(ZoneOffset.UTC)), utc(since), utc(upTo), limit);
    }

    /** DB 는 UTC 벽시계 시각을 담는다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    public record Closed(UUID productId, Instant closesAt) {
    }
}
