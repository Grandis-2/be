package com.grandis.nova.preorder.preorder.domain;

import com.grandis.nova.preorder.preorder.PaymentOverdue;
import com.grandis.nova.preorder.preorder.PreorderStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 결제 기한이 지난 REGISTERED 예약. 상품을 가리지 않아 ix_preorder_payable(status, payable_from) 로 범위를 읽는다.
 * 주문 쪽에서 취소가 거절돼 REGISTERED 로 돌아온 적이 있는 예약은 만료 대상에서 빼고 따로 센다 — 결제됐는데 결제 확인을
 * 못 받은 예약이면 만료 → 거절이 끝없이 되풀이되므로, 사람이 보게 한다.
 */
@Component
public class PaymentOverdueQuery {

    private static final String OVERDUE = " FROM preorders p WHERE p.status = :status AND p.payable_from <= :dueBefore";
    private static final String REJECTED_BEFORE = """
            EXISTS (SELECT 1 FROM preorder_events e
                     WHERE e.preorder_id = p.id AND e.from_status = :canceling AND e.to_status = :status)""";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    PaymentOverdueQuery(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 만료할 예약의 공개 UUID. 기한이 오래된 것부터 limit 건. */
    public List<String> findTokens(Instant dueBefore, int limit) {
        return jdbcTemplate.queryForList("SELECT p.preorder_token" + OVERDUE + " AND NOT " + REJECTED_BEFORE
                + " ORDER BY p.payable_from LIMIT :limit", params(dueBefore).addValue("limit", limit), String.class);
    }

    public PaymentOverdue count(Instant dueBefore) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*), COALESCE(SUM(" + REJECTED_BEFORE + "), 0)" + OVERDUE,
                params(dueBefore), (rs, row) -> new PaymentOverdue(rs.getLong(1) - rs.getLong(2), rs.getLong(2)));
    }

    private static MapSqlParameterSource params(Instant dueBefore) {
        return new MapSqlParameterSource()
                .addValue("status", PreorderStatus.REGISTERED.name())
                .addValue("canceling", PreorderStatus.CANCELING.name())
                .addValue("dueBefore", LocalDateTime.ofInstant(dueBefore, ZoneOffset.UTC));
    }
}
