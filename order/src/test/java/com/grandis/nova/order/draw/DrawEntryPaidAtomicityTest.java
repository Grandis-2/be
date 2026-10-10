package com.grandis.nova.order.draw;

import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.util.UUID;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 결제 완료와 Mock 전송 이벤트는 한 트랜잭션이다 — 이벤트를 적은 뒤 실패하면 결제 완료도 남지 않는다(다시 받으면 둘 다 다시 한다).
 * 이벤트가 별도 트랜잭션이면 결제 완료 없이 Mock 에 응모가 가거나, 결제 완료인데 이벤트가 없어 추첨에서 빠진다.
 */
@OrderIntegrationTest
class DrawEntryPaidAtomicityTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DrawEntryPaymentResults results;
    @MockitoSpyBean OutboxWriter outbox;

    @Test
    @DisplayName("이벤트는 결제 완료와 같은 트랜잭션에서 적고, 그 뒤 실패하면 둘 다 남지 않는다")
    void paidAndEventCommitTogether() {
        OrderFixtures fixtures = new OrderFixtures(jdbcTemplate);
        OrderFixtures.StockProduct product = fixtures.inStockProduct(1);
        UUID draw = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO draw_campaigns (id, idempotency_key, product_id, option_id, title, product_title_snapshot, option_title_snapshot,
                                            entry_fee, winner_count, opens_at, closes_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 't', 'p', 'o', 100, 1, UTC_TIMESTAMP(6) - INTERVAL 1 HOUR, UTC_TIMESTAMP(6) + INTERVAL 1 DAY,
                        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                bytes(draw), draw.toString(), bytes(product.productId()), bytes(product.optionIds().getFirst()));
        UUID entry = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO draw_entries (id, campaign_id, customer_id, status, authorizing_provider_order_id, ship_to_name, ship_to_phone,
                                          ship_to_postal_code, ship_to_line1, created_at, updated_at)
                VALUES (?, ?, ?, 'AUTHORIZING', 'atomic_window_1', '홍길동', '010-0000-0000', '04524', '서울시', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                bytes(entry), bytes(draw), bytes(fixtures.customer()));
        String[] seenWhenAppending = new String[1];
        doAnswer(invocation -> {
            // 같은 트랜잭션이면 이 연결에서 아직 커밋 안 된 결제 완료가 보인다. 별도 트랜잭션(다른 연결)이면 커밋된 승인 중이 보인다
            seenWhenAppending[0] = jdbcTemplate.queryForObject("SELECT status FROM draw_entries WHERE id = ?", String.class, (Object) bytes(entry));
            invocation.callRealMethod();
            throw new IllegalStateException("이벤트를 적은 뒤 실패");
        }).when(AopTestUtils.<OutboxWriter>getUltimateTargetObject(outbox)).append(any()); // 트랜잭션 프록시 안쪽 대역에 건다

        assertThatThrownBy(() -> results.approved(entry, "atomic_window_1")).isInstanceOf(IllegalStateException.class);
        assertThat(seenWhenAppending[0]).as("이벤트는 결제 완료와 같은 트랜잭션에서 적는다").isEqualTo("PAID");

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM draw_entries WHERE id = ?", String.class, (Object) bytes(entry)))
                .isEqualTo("AUTHORIZING");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_outbox_events WHERE aggregate_type = 'DRAW_ENTRY' AND aggregate_id = ?",
                Long.class, (Object) bytes(entry))).isZero();
    }
}
