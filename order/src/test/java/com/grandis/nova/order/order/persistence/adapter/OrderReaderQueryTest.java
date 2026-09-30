package com.grandis.nova.order.order.persistence.adapter;

import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderEvent;
import com.grandis.nova.order.order.domain.repository.OrderPosition;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.PlacedOrders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 조회용 읽기 포트. 원장이 만든 행을 운영과 같은 쿼리로 읽는다. */
@OrderIntegrationTest
class OrderReaderQueryTest {

    @Autowired
    OrderReader reader;

    @Autowired
    OrderLedger ledger;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TransactionTemplate transactionTemplate;

    PlacedOrders orders;
    Long customerId;

    @BeforeEach
    void setUp() {
        OrderFixtures fixtures = new OrderFixtures(jdbcTemplate);
        orders = new PlacedOrders(ledger, transactionTemplate, fixtures);
        customerId = fixtures.customer();
    }

    /*
     * 주문을 읽은 뒤 다른 트랜잭션이 전이를 커밋한 상황. 이력 번호는 주문 행의 카운터와 같은 UPDATE 에서 오르므로,
     * 카운터보다 큰 번호의 이력은 "읽은 주문보다 나중" 이다. SQL 로 그런 이력을 만들어 빠지는지 본다.
     */
    @Test
    void eventsStopAtSequenceOfOrderThatWasRead() {
        Order order = orders.place(customerId);
        jdbcTemplate.update("""
                INSERT INTO order_events (order_id, event_sequence, from_status, to_status, actor, reason, created_at)
                VALUES (?, 2, 'AWAITING_PAYMENT', 'CANCELED', 'SYSTEM', 'LATER', UTC_TIMESTAMP(6))
                """, order.id());

        List<OrderEvent> events = reader.findEvents(order.id(), order.eventSequence());

        assertThat(events).extracting(OrderEvent::eventSequence).containsExactly(1L);
        assertThat(reader.findEvents(order.id(), 2)).extracting(OrderEvent::eventSequence).containsExactly(1L, 2L);
    }

    @Test
    void itemsAreReadForManyOrdersAtOnce() {
        Order first = orders.place(customerId);
        Order second = orders.place(customerId);

        assertThat(reader.findItemsByOrderIds(List.of(second.id(), first.id())))
                .extracting(item -> item.orderId())
                .containsExactly(first.id(), second.id());
    }

    /*
     * 같은 시각의 주문이 페이지 경계에 걸려도 id 로 갈라 새거나 겹치지 않는다.
     * 시각은 과거로 둔다 — 관리자 목록 테스트가 "방금 만든 주문이 최신" 이라고 가정한다.
     */
    @Test
    void customerOrdersPageThroughSameCreatedAtWithoutGapOrOverlap() {
        List<Long> ids = List.of(orders.place(customerId).id(), orders.place(customerId).id(),
                orders.place(customerId).id());
        Instant sameInstant = Instant.parse("2020-01-01T00:00:00.123456Z");
        ids.forEach(id -> jdbcTemplate.update("UPDATE orders SET created_at = ? WHERE id = ?",
                Timestamp.from(sameInstant), id));

        List<Long> seen = new java.util.ArrayList<>();
        OrderPosition after = null;
        for (int page = 0; page < ids.size() + 1; page++) {
            List<Order> found = reader.findByCustomer(customerId, after, 1);
            if (found.isEmpty()) {
                break;
            }
            Order last = found.getFirst();
            seen.add(last.id());
            after = new OrderPosition(last.createdAt(), last.id());
        }

        assertThat(seen).containsExactlyElementsOf(ids.reversed());
    }

    /* 회원 조건은 권한 범위다. 빠지면 전 회원의 주문이 아니라 실패여야 한다. */
    @Test
    void customerListWithoutCustomerFailsClosed() {
        org.assertj.core.api.Assertions.assertThatNullPointerException()
                .isThrownBy(() -> reader.findByCustomer(null, null, 10));
    }
}
