package com.grandis.nova.order;

import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.TestIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * orders.idempotency_key 의 제약이 마이그레이션 그대로 막고 여는지 — 엔티티를 거치지 않고 SQL 로 직접 친다(CartAndOrderItemSchemaTest 와 같다).
 * 넣은 행은 다른 시험(관리자 주문 목록)이 읽으므로 앱이 만들 수 있는 행(결제 대기 · 기한 있음 · 이력 번호 1)으로 넣는다.
 */
@OrderIntegrationTest
class BuyNowIdempotencySchemaTest {

    @Autowired JdbcTemplate jdbcTemplate;

    UUID customerId;

    @BeforeEach
    void setUp() {
        customerId = new OrderFixtures(jdbcTemplate).customer();
    }

    @Test
    @DisplayName("멱등 키는 바로 구매 주문에만 있고, 바로 구매 주문은 반드시 갖는다(ck_order_idempotency)")
    void keyBelongsOnlyAndAlwaysToBuyNow() {
        assertThatThrownBy(() -> insert(customerId, "CART", UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_order_idempotency");
        assertThatThrownBy(() -> insert(customerId, "BUY_NOW", null))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_order_idempotency");
    }

    @Test
    @DisplayName("같은 회원의 같은 키는 한 행(uq_order_idempotency) — 다른 회원의 같은 키 · 키 없는 다른 출처 행끼리는 겹쳐도 된다")
    void keyIsUniquePerCustomer() {
        UUID key = UUID.randomUUID();
        insert(customerId, "BUY_NOW", key);

        assertThatThrownBy(() -> insert(customerId, "BUY_NOW", key))
                .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uq_order_idempotency");
        insert(new OrderFixtures(jdbcTemplate).customer(), "BUY_NOW", key);
        insert(customerId, "CART", null);
        insert(customerId, "CART", null);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders WHERE customer_id = ?", Long.class, (Object) bytes(customerId)))
                .isEqualTo(3);
    }

    private void insert(UUID customer, String source, UUID key) {
        jdbcTemplate.update("""
                INSERT INTO orders (id, customer_id, source, idempotency_key, status, total_amount, payment_due_at,
                                    ship_to_name, ship_to_phone, ship_to_postal_code, ship_to_line1, event_sequence, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'AWAITING_PAYMENT', 0, UTC_TIMESTAMP(6), 'a', '1', '1', 'x', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                bytes(TestIds.next()), bytes(customer), source, key == null ? null : bytes(key));
    }
}
