package com.grandis.nova.order.order.persistence.adapter;

import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderDraft;
import com.grandis.nova.order.order.domain.model.OrderItem;
import com.grandis.nova.order.order.domain.model.OrderLine;
import com.grandis.nova.order.order.domain.repository.ExpiredOrderPosition;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.domain.repository.OrderWriter;
import com.grandis.nova.order.order.persistence.entity.OrderItemJpaEntity;
import com.grandis.nova.order.order.persistence.repository.OrderItemJpaRepository;
import com.grandis.nova.order.order.vo.Money;
import com.grandis.nova.order.order.vo.Quantity;
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
import java.util.List;
import java.util.UUID;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
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
    UUID customerId;
    UUID preorderId;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        product = fixtures.preorderProduct();
        customerId = fixtures.customer();
        preorderId = OrderFixtures.preorderId();
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
        Order placed = Order.place(draft, Instant.EPOCH);
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
        assertThat(reader.findByPreorderId(preorderId)).contains(loaded);
        assertThat(reader.findItems(stored.id())).map(OrderItem::line).containsExactlyElementsOf(draft.lines());
    }

    // 같이 도는 구버전은 order_token 을 NOT NULL 로 읽고 그 값으로 주문을 찾는다. 새 버전은 쓰지 않으므로 DB 가 id 의 문자열로 채운다.
    @Test
    void orderTokenIsFilledWithIdForOldVersion() {
        Order stored = writer.insert(Order.place(preorderCommand(customerId, preorderId, product).toDraft(), Instant.EPOCH));

        assertThat(jdbcTemplate.queryForObject("SELECT order_token FROM orders WHERE id = ?", String.class, bytes(stored.id())))
                .isEqualTo(stored.id().toString());
    }

    // 저장된 주문을 다시 넣거나 결제 대기가 아닌 주문을 넣으면 상태 머신 · 이력을 거치지 않은 주문이 생긴다.
    @Test
    void insertAcceptsOnlyNewAwaitingPaymentOrders() {
        Order placed = Order.place(preorderCommand(customerId, preorderId, product).toDraft(), Instant.EPOCH);
        Order delivered = new Order(null, customerId, OrderSource.PREORDER, preorderId,
                placed.preorderToken(), OrderStatus.DELIVERED, null, placed.totalAmount(), null, null, null, placed.shipTo(), 5, null, null);
        Order stored = writer.insert(placed);

        assertThatThrownBy(() -> writer.insert(delivered))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("status=DELIVERED")
                .hasMessageNotContaining("홍길동");
        assertThatThrownBy(() -> writer.insert(stored)).hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE customer_id = ?", Integer.class, bytes(customerId))).isEqualTo(1);
    }

    /*
     * 상태 변경은 바꾼 주문만 떼어낸다. 컨텍스트 전체를 비우면 같은 트랜잭션에서 읽어 둔 다른 엔티티(결제 · 재고 등)가
     * 떼어져 그 뒤의 변경이 dirty checking 에 잡히지 않는다. 옛 상태를 든 주문 엔티티는 떼어져야 다음 조회가 새 상태를 본다.
     */
    @Test
    void changeStatusDetachesOnlyTheChangedOrder() {
        OrderDraft draft = preorderCommand(customerId, preorderId, product).toDraft();
        Order stored = writer.insert(Order.place(draft, Instant.EPOCH));
        writer.insertItems(stored.id(), draft.lines());
        entityManager.clear();
        assertThat(reader.findById(stored.id())).get().extracting(Order::status).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        OrderItemJpaEntity otherManaged = items.findByOrderIdOrderById(stored.id()).getFirst();

        writer.changeStatus(stored.id(), OrderStatus.AWAITING_PAYMENT, OrderStatus.CANCELED, null, Instant.now());

        assertThat(entityManager.contains(otherManaged)).isTrue();
        assertThat(reader.findById(stored.id())).get().extracting(Order::status).isEqualTo(OrderStatus.CANCELED);
    }

    @Test
    void missingOrderIsEmpty() {
        assertThat(reader.findById(UUID.randomUUID())).isEmpty();
        assertThat(reader.findByPreorderId(UUID.randomUUID())).isEmpty();
        assertThat(writer.lockStatus(UUID.randomUUID())).isEmpty();
    }

    /** 보증 칸까지 왕복하고, 결제 안 된 장바구니 주문은 결제 대기 · 승인 중이면 기한과 상관없이 전부다(기한이 지나도 확보를 쥐고 있다). */
    @Test
    void cartOrderRoundTripsWarrantyAndUnpaidIgnoresDue() {
        Instant placedAt = Instant.parse("2026-10-10T06:00:00.123456Z");
        OrderFixtures.StockProduct stocked = fixtures.inStockProduct(1);
        UUID option = stocked.optionIds().getFirst();
        OrderDraft draft = new OrderDraft(customerId, OrderSource.CART, null, null,
                new ShipTo("홍길동", "010-0000-0000", "04524", "세종대로 110", null), List.of(
                new OrderLine(stocked.productId(), option, new Quantity(3), Money.won(1_000), 2, Money.won(300), "상품", "옵션")));
        Order stored = writer.insert(Order.place(draft, placedAt));
        writer.insertItems(stored.id(), draft.lines());
        entityManager.flush();
        entityManager.clear();

        OrderLine line = reader.findItems(stored.id()).getFirst().line();
        assertThat(line.warrantyQuantity()).isEqualTo(2);
        assertThat(line.warrantyUnitPrice()).isEqualTo(Money.won(300));
        Order future = writer.insert(Order.place(draft, Instant.now().plusSeconds(3_600)));
        writer.insertItems(future.id(), draft.lines());
        UUID other = fixtures.customer();
        Order othersOrder = writer.insert(Order.place(new OrderDraft(other, OrderSource.CART, null, null, draft.shipTo(), draft.lines()),
                placedAt));
        writer.insertItems(othersOrder.id(), draft.lines());
        Order preorder = writer.insert(Order.place(preorderCommand(customerId, preorderId, product).toDraft(), placedAt));
        writer.insertItems(preorder.id(), preorderCommand(customerId, preorderId, product).toDraft().lines());
        assertThat(reader.findUnpaidCartOrders(customerId)).as("기한이 지났든(과거) 아직이든(미래), 이 회원의 장바구니 주문만 — 결제 대기인 사전예약 주문 · 다른 회원 주문은 빠진다")
                .extracting(Order::id).containsExactlyInAnyOrder(stored.id(), future.id());
        fixtures.forceStatus(future.id(), "CANCELED");

        fixtures.forceAuthorizing(stored.id(), "p-1");
        assertThat(reader.findUnpaidCartOrders(customerId)).as("승인 중도").hasSize(1);
        fixtures.forceStatus(stored.id(), "AWAITING_CONFIRMATION");
        assertThat(reader.findUnpaidCartOrders(customerId)).as("결제됨은 아니다").isEmpty();
        fixtures.forceStatus(stored.id(), "CANCELED");
        assertThat(reader.findUnpaidCartOrders(customerId)).isEmpty();
    }

    /**
     * 기한 만료는 결제 대기인 장바구니 · 바로 구매 주문을 고르고(기한 없는 사전예약은 아니다), "회원당 하나" 는 장바구니 주문만 센다.
     * 공유 DB 의 다른 주문(지금 시각대 기한)이 섞이지 않게 아주 옛 시각에 만들어 그 기한으로 묻는다.
     */
    @Test
    void expiredAwaitingIncludesBuyNowButUnpaidCartOrdersDoNot() {
        Instant placedAt = Instant.parse("2000-01-01T00:00:00Z");
        OrderFixtures.StockProduct stocked = fixtures.inStockProduct(1);
        List<OrderLine> lines = List.of(new OrderLine(stocked.productId(), stocked.optionIds().getFirst(), new Quantity(1), Money.won(1_000), 0,
                Money.ZERO, "상품", "옵션"));
        ShipTo shipTo = new ShipTo("홍길동", "010-0000-0000", "04524", "세종대로 110", null);
        Order cart = writer.insert(Order.place(new OrderDraft(customerId, OrderSource.CART, null, null, shipTo, lines), placedAt));
        writer.insertItems(cart.id(), lines);
        Order buyNow = writer.insert(Order.place(new OrderDraft(customerId, OrderSource.BUY_NOW, null, null, shipTo, lines), placedAt));
        writer.insertItems(buyNow.id(), lines);
        Order preorder = writer.insert(Order.place(preorderCommand(customerId, preorderId, product).toDraft(), placedAt));
        writer.insertItems(preorder.id(), preorderCommand(customerId, preorderId, product).toDraft().lines());
        entityManager.flush();
        entityManager.clear();

        assertThat(reader.findExpiredAwaitingOrders(placedAt.plus(Order.PAYMENT_WINDOW), ExpiredOrderPosition.START, 100))
                .extracting(Order::id).containsExactlyInAnyOrder(cart.id(), buyNow.id());
        assertThat(reader.findUnpaidCartOrders(customerId)).as("바로 구매 주문은 회원당 하나 규칙 밖")
                .extracting(Order::id).containsExactly(cart.id());
    }
}
