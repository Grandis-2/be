package com.grandis.nova.order.order;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.enums.OrderTrigger;
import com.grandis.nova.order.order.domain.exception.OrderAlreadyPlacedException;
import com.grandis.nova.order.order.domain.model.OrderTransition;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.order.support.OrderFixtures.PreorderProduct;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static com.grandis.nova.order.support.OrderFixtures.preorderCommand;
import static org.assertj.core.api.Assertions.assertThat;

/** 별도 트랜잭션에서 동시에 들어오는 생성 · 전이. 각 작업이 커밋한다. */
@OrderIntegrationTest
class OrderLedgerConcurrencyTest {

    static final int REQUESTS = 5;

    @Autowired
    OrderLedger ledger;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    PreorderProduct product;
    UUID customerId;
    UUID preorderId;

    @BeforeEach
    void setUp() {
        OrderFixtures fixtures = new OrderFixtures(jdbcTemplate);
        product = fixtures.preorderProduct();
        customerId = fixtures.customer();
        preorderId = fixtures.payablePreorder(customerId, product, 1);
    }

    // 잠금 없이 조건부 UPDATE 만 쓰면 늦은 쪽이 0행으로 예외가 된다. 행을 잠그고 판정하므로 늦은 쪽은 "변화 없음" 이다.
    @Test
    void concurrentCancelRequestsApplyOnceAndNoneFails() throws Exception {
        UUID id = transactionTemplate.execute(status ->
                ledger.place(preorderCommand(customerId, preorderId, product).toDraft(), EventCause.user()).id());

        List<Outcome<OrderTransition>> outcomes = Concurrently.run(REQUESTS, i -> () -> transactionTemplate.execute(
                status -> ledger.fire(id, OrderTrigger.CANCEL_REQUESTED, EnumSet.of(OrderStatus.AWAITING_PAYMENT),
                        EventCause.system("USER"))));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        assertThat(outcomes.stream().map(Outcome::value).filter(OrderTransition::applied)).hasSize(1);
        assertThat(outcomes).allSatisfy(o -> assertThat(o.value().status()).isEqualTo(OrderStatus.CANCELED));
        assertThat(jdbcTemplate.queryForList(
                "SELECT event_sequence FROM order_events WHERE order_id = ? ORDER BY event_sequence", Long.class, bytes(id)))
                .containsExactly(1L, 2L);
    }

    @Test
    void concurrentPlacesForSamePreorderLeaveOneOrder() throws Exception {
        List<Outcome<UUID>> outcomes = Concurrently.run(REQUESTS, i -> () -> transactionTemplate.execute(status ->
                ledger.place(preorderCommand(customerId, preorderId, product).toDraft(), EventCause.user()).id()));

        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()))
                .hasSize(REQUESTS - 1)
                .allSatisfy(o -> assertThat(o.error())
                        .isInstanceOf(OrderAlreadyPlacedException.class));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM order_events e JOIN orders o ON o.id = e.order_id WHERE o.preorder_id = ?
                """, Integer.class, bytes(preorderId))).isEqualTo(1);
    }
}
