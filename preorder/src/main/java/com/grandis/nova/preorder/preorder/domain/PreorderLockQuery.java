package com.grandis.nova.preorder.preorder.domain;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.preorder.preorder.LockedPreorder;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * 예약 행을 SELECT … FOR UPDATE 로 잠그며 전이에 필요한 칸을 함께 읽는다 — 잠근 뒤 순번 · 결제 시각을 다시 읽지 않게.
 * JPA 를 거치지 않는다: 같은 트랜잭션에서 먼저 읽은 엔티티가 있으면 JPQL 잠금 조회는 잠근 뒤의 값 대신 그 객체를 돌려준다.
 * 잠금 순서는 예약 행 → 그 예약의 작업 행이다. 작업 행을 먼저 잠근 뒤 이걸 부르지 않는다(교착).
 */
@Component
public class PreorderLockQuery {

    private static final String COLUMNS = """
            SELECT id, preorder_token, customer_id, product_id, status, payable_from, reserved_at, external_reference,
                   event_sequence
              FROM preorders
            """;

    private static final RowMapper<LockedPreorder> MAPPER = (rs, rowNum) -> new LockedPreorder(
            UuidBinary.fromBytes(rs.getBytes(1)), rs.getString(2), UuidBinary.fromBytes(rs.getBytes(3)),
            UuidBinary.fromBytes(rs.getBytes(4)), PreorderStatus.valueOf(rs.getString(5)),
            instant(rs.getObject(6, LocalDateTime.class)), instant(rs.getObject(7, LocalDateTime.class)),
            rs.getString(8), rs.getLong(9));

    private final JdbcTemplate jdbcTemplate;

    PreorderLockQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<LockedPreorder> lock(UUID id) {
        return jdbcTemplate.query(COLUMNS + " WHERE id = ? FOR UPDATE", MAPPER, (Object) UuidBinary.toBytes(id))
                .stream().findFirst();
    }

    public Optional<LockedPreorder> lockByToken(String preorderToken) {
        return jdbcTemplate.query(COLUMNS + " WHERE preorder_token = ? FOR UPDATE", MAPPER, preorderToken)
                .stream().findFirst();
    }

    /** DB 는 UTC 벽시계 시각을 담는다. */
    private static Instant instant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }
}
