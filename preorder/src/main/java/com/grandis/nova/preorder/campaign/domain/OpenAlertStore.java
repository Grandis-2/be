package com.grandis.nova.preorder.campaign.domain;

import com.grandis.nova.common.UuidBinary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * 오픈 알림 신청(preorder_open_alerts). 행이 (상품, 회원) 하나뿐이라 엔티티 없이 SQL 로 쓴다.
 * 신청 · 취소는 다시 해도 결과가 같다. 회차 행 잠금 · 판정은 호출하는 쪽이 한다.
 */
@Component
public class OpenAlertStore {

    private static final String SUBSCRIBE = """
            INSERT INTO preorder_open_alerts (product_id, customer_id, created_at) VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE product_id = product_id
            """;

    private static final String UNSUBSCRIBE = "DELETE FROM preorder_open_alerts WHERE product_id = ? AND customer_id = ?";

    private static final String EXISTS = "SELECT COUNT(*) FROM preorder_open_alerts WHERE product_id = ? AND customer_id = ?";

    /** 공개된 오픈 전 회차만. 판매 중지는 보인다 — 신청을 끌 수 있게. */
    private static final String MINE = """
            SELECT c.product_id, c.product_title, c.opens_at, c.closes_at
              FROM preorder_open_alerts a
              JOIN preorder_campaigns c ON c.product_id = a.product_id
             WHERE a.customer_id = ? AND c.opens_at > ? AND c.visible = 1
             ORDER BY c.opens_at, c.product_id
             LIMIT ?
            """;

    private static final String RECIPIENTS = """
            SELECT customer_id FROM preorder_open_alerts
             WHERE product_id = ? AND customer_id > ?
             ORDER BY customer_id
             LIMIT ?
            """;

    /** 첫 묶음의 커서. binary(16) 비교에서 모든 회원 id 보다 작다. */
    private static final byte[] BEFORE_FIRST = new byte[16];

    private final JdbcTemplate jdbcTemplate;

    OpenAlertStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 이미 신청했으면 그대로 둔다. 회차가 없으면 외래 키 위반이다. */
    public void subscribe(UUID productId, UUID customerId, Instant now) {
        jdbcTemplate.update(SUBSCRIBE, UuidBinary.toBytes(productId), UuidBinary.toBytes(customerId), utc(now));
    }

    public void unsubscribe(UUID productId, UUID customerId) {
        jdbcTemplate.update(UNSUBSCRIBE, UuidBinary.toBytes(productId), UuidBinary.toBytes(customerId));
    }

    public boolean exists(UUID productId, UUID customerId) {
        Integer count = jdbcTemplate.queryForObject(EXISTS, Integer.class, UuidBinary.toBytes(productId),
                UuidBinary.toBytes(customerId));
        return count != null && count > 0;
    }

    /** 그 회원이 신청한, now 뒤에 열리는 공개 회차. 오픈이 이른 순. */
    public List<MyOpenAlert> findMine(UUID customerId, Instant now, int limit) {
        return jdbcTemplate.query(MINE, OpenAlertStore::myOpenAlert, UuidBinary.toBytes(customerId), utc(now), limit);
    }

    /**
     * 그 상품의 신청자를 회원 id 순으로 after 다음부터 limit 명. 첫 묶음은 after 를 null 로 준다.
     * 잠그지 않는다 — 오픈 표시가 커밋된 뒤의 트랜잭션에서 부르므로, 표시 전에 들어온 신청은 이미 커밋돼 보이고 표시 뒤의 신청은
     * 거절돼 없다(OpenAlertService.subscribe).
     */
    public List<UUID> recipients(UUID productId, UUID after, int limit) {
        return jdbcTemplate.query(RECIPIENTS, (rs, rowNum) -> UuidBinary.fromBytes(rs.getBytes(1)),
                UuidBinary.toBytes(productId), after == null ? BEFORE_FIRST : UuidBinary.toBytes(after), limit);
    }

    private static MyOpenAlert myOpenAlert(ResultSet rs, int rowNum) throws SQLException {
        return new MyOpenAlert(UuidBinary.fromBytes(rs.getBytes("product_id")), rs.getString("product_title"),
                instant(rs, "opens_at"), instant(rs, "closes_at"));
    }

    /** DB 는 UTC 벽시계 시각을 담는다. */
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    /** @param title 상품명 사본. catalog 값을 받기 전이면 null */
    public record MyOpenAlert(UUID productId, String title, Instant opensAt, Instant closesAt) {
    }
}
