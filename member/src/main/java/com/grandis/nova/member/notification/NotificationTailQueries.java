package com.grandis.nova.member.notification;

import com.grandis.nova.common.UuidBinary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link NotificationTail} 의 조회 둘. 둘 다 짧은 문장 하나라 트랜잭션 없이 연결을 바로 돌려준다.
 * 새 행은 기본키(v7 이라 시각순) 범위로, 안 읽은 수는 ix_notification_customer_unread 로 읽는다.
 */
@Component
class NotificationTailQueries {

    private static final String FROM = "SELECT id, customer_id FROM notifications WHERE id >= ? ORDER BY id LIMIT ?";
    private static final String AFTER = "SELECT id, customer_id FROM notifications WHERE id > ? ORDER BY id LIMIT ?";
    private static final String UNREAD = """
            SELECT customer_id, COUNT(*) FROM notifications
             WHERE customer_id IN (%s) AND read_at IS NULL
             GROUP BY customer_id""";

    private final JdbcTemplate jdbc;

    NotificationTailQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 한 행의 알림 id · 받는 회원. */
    record Row(UUID id, UUID customerId) {
    }

    /** @param inclusive true 면 bound 부터, false 면 bound 다음부터(앞 쪽의 마지막 id 를 넘길 때) */
    List<Row> page(UUID bound, boolean inclusive, int limit) {
        return jdbc.query(inclusive ? FROM : AFTER,
                (rs, i) -> new Row(UuidBinary.fromBytes(rs.getBytes(1)), UuidBinary.fromBytes(rs.getBytes(2))),
                UuidBinary.toBytes(bound), limit);
    }

    /** @return 안 읽은 알림이 있는 회원만. 없는 회원은 0 이다 */
    Map<UUID, Long> unreadCounts(Collection<UUID> customerIds) {
        Map<UUID, Long> counts = new HashMap<>();
        jdbc.query(UNREAD.formatted(String.join(", ", Collections.nCopies(customerIds.size(), "?"))),
                rs -> {
                    counts.put(UuidBinary.fromBytes(rs.getBytes(1)), rs.getLong(2));
                },
                customerIds.stream().map(UuidBinary::toBytes).toArray());
        return counts;
    }
}
