package com.grandis.nova.order.order.persistence.adapter;

import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderDraft;
import com.grandis.nova.order.order.domain.model.OrderItem;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.domain.repository.OrderWriter;
import com.grandis.nova.order.order.persistence.entity.OrderItemJpaEntity;
import com.grandis.nova.order.order.persistence.repository.OrderItemJpaRepository;
import com.grandis.nova.order.order.vo.OrderToken;
import com.grandis.nova.order.order.vo.ShipTo;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderFixtures.PreorderProduct;
import com.grandis.nova.order.support.OrderIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static com.grandis.nova.order.support.OrderFixtures.preorderCommand;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@OrderIntegrationTest
@Transactional
class JpaOrderStoreTest {

    @Autowired
    OrderReader reader;

    @Autowired
    OrderWriter writer;

    @Autowired
    EntityManager entityManager;

    @Autowired
    OrderItemJpaRepository items;

    @Autowired
    JdbcTemplate jdbcTemplate;

    OrderFixtures fixtures;
    PreorderProduct product;
    Long customerId;
    Long preorderId;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        product = fixtures.preorderProduct();
        customerId = fixtures.customer();
        preorderId = fixtures.payablePreorder(customerId, product, 1);
    }

    /*
     * 도메인 → 행 → 도메인 왕복. 영속성 컨텍스트를 비운 뒤 읽어야 DB 행에서 엔티티를 만드는 경로를 탄다 —
     * 비우지 않으면 방금 저장한 엔티티를 그대로 돌려받아 칼럼 매핑 · 타입 변환 오류가 드러나지 않는다.
     */
    @Test
    void storedOrderAndItemsReadBackFromDatabaseUnchanged() {
        OrderDraft draft = new OrderDraft(customerId, OrderSource.PREORDER, preorderId, OrderFixtures.preorderToken(preorderId),
                new ShipTo("홍길동", "010-0000-0000", "04524", "세종대로 110", "3층"),
                preorderCommand(customerId, preorderId, product).toDraft().lines());
        Order placed = Order.place(draft, OrderToken.issue());
        Order stored = writer.insert(placed);
        writer.insertItems(stored.id(), draft.lines());
        entityManager.clear();

        Order loaded = reader.findById(stored.id()).orElseThrow();

        assertThat(loaded).usingRecursiveComparison()
                .ignoringFields("id", "createdAt", "updatedAt")
                .isEqualTo(placed);
        assertThat(loaded.id()).isEqualTo(stored.id());
        // 시계가 DB 해상도(마이크로초)라 저장하고 돌려준 시각과 DB 행의 시각이 같다(common:jpa StorageClock).
        assertThat(loaded.createdAt()).isEqualTo(stored.createdAt());
        assertThat(reader.findByOrderToken(placed.orderToken())).contains(loaded);
        assertThat(reader.findByPreorderId(preorderId)).contains(loaded);
        assertThat(reader.findItems(stored.id())).map(OrderItem::line).containsExactlyElementsOf(draft.lines());
    }

    // 저장된 주문을 다시 넣거나 결제 대기가 아닌 주문을 넣으면 상태 머신 · 이력을 거치지 않은 주문이 생긴다.
    @Test
    void insertAcceptsOnlyNewAwaitingPaymentOrders() {
        Order placed = Order.place(preorderCommand(customerId, preorderId, product).toDraft(), OrderToken.issue());
        Order delivered = new Order(null, placed.orderToken(), customerId, OrderSource.PREORDER, preorderId,
                placed.preorderToken(), OrderStatus.DELIVERED, placed.totalAmount(), null, null, placed.shipTo(), null, 5, null, null);
        Order stored = writer.insert(placed);

        assertThatThrownBy(() -> writer.insert(delivered))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status=DELIVERED")
                .hasMessageNotContaining("홍길동");
        assertThatThrownBy(() -> writer.insert(stored)).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE customer_id = ?", Integer.class, customerId)).isEqualTo(1);
    }

    /*
     * 상태 변경은 바꾼 주문만 떼어낸다. 컨텍스트 전체를 비우면 같은 트랜잭션에서 읽어 둔 다른 엔티티(결제 · 재고 등)가
     * 떼어져 그 뒤의 변경이 dirty checking 에 잡히지 않는다. 옛 상태를 든 주문 엔티티는 떼어져야 다음 조회가 새 상태를 본다.
     */
    @Test
    void changeStatusDetachesOnlyTheChangedOrder() {
        OrderDraft draft = preorderCommand(customerId, preorderId, product).toDraft();
        Order stored = writer.insert(Order.place(draft, OrderToken.issue()));
        writer.insertItems(stored.id(), draft.lines());
        entityManager.clear();
        assertThat(reader.findById(stored.id())).get().extracting(Order::status).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        OrderItemJpaEntity otherManaged = items.findByOrderIdOrderById(stored.id()).getFirst();

        writer.changeStatus(stored.id(), OrderStatus.AWAITING_PAYMENT, OrderStatus.CANCELED, Instant.now());

        assertThat(entityManager.contains(otherManaged)).isTrue();
        assertThat(reader.findById(stored.id())).get().extracting(Order::status).isEqualTo(OrderStatus.CANCELED);
    }

    @Test
    void missingOrderIsEmpty() {
        assertThat(reader.findById(Long.MAX_VALUE)).isEmpty();
        assertThat(reader.findByOrderToken(OrderToken.issue())).isEmpty();
        assertThat(reader.findByPreorderId(Long.MAX_VALUE)).isEmpty();
        assertThat(writer.lockStatus(Long.MAX_VALUE)).isEmpty();
    }
}
