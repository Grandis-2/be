package com.grandis.nova.order.order;

import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.exception.OrderAlreadyPlacedException;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderDraft;
import com.grandis.nova.order.order.domain.model.OrderTransition;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.order.vo.Money;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderFixtures.PreorderProduct;
import com.grandis.nova.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static com.grandis.nova.order.order.domain.enums.OrderStatus.AWAITING_CONFIRMATION;
import static com.grandis.nova.order.order.domain.enums.OrderStatus.AWAITING_PAYMENT;
import static com.grandis.nova.order.order.domain.enums.OrderStatus.CANCELED;
import static com.grandis.nova.order.order.domain.enums.OrderStatus.SHIPPED;
import static com.grandis.nova.order.order.domain.enums.OrderTrigger.CANCEL_REQUESTED;
import static com.grandis.nova.order.support.OrderFixtures.UNIT_PRICE;
import static com.grandis.nova.order.support.OrderFixtures.preorderCommand;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@OrderIntegrationTest
@Transactional
class OrderLedgerTest {

    /** 이번 에픽의 예약 취소 수신이 쓰는 전제: 미결제 주문만 취소한다. */
    static final Set<OrderStatus> UNPAID = EnumSet.of(AWAITING_PAYMENT);
    static final Set<OrderStatus> ANY = EnumSet.allOf(OrderStatus.class);

    @Autowired
    OrderLedger ledger;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TransactionTemplate transactionTemplate;

    OrderFixtures fixtures;
    PreorderProduct product;
    Long customerId;
    final AtomicLong nextPosition = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        product = fixtures.preorderProduct();
        customerId = fixtures.customer();
    }

    @Test
    void placeWritesOrderItemAndFirstEvent() {
        Long preorderId = preorder();

        Order order = ledger.place(draft(preorderId), EventCause.user());

        assertThat(order.id()).isNotNull();
        assertThat(order.createdAt()).isNotNull();
        assertThat(order.totalAmount()).isEqualTo(new Money(UNIT_PRICE));
        assertThat(row(order.id()))
                .containsEntry("order_token", order.orderToken().value())
                .containsEntry("status", "AWAITING_PAYMENT")
                .containsEntry("event_sequence", 1L)
                .containsEntry("source", "PREORDER")
                .containsEntry("preorder_id", preorderId)
                .containsEntry("payment_due_at", null)
                .containsEntry("ship_to_name", "홍길동")
                .containsEntry("ship_to_line2", null);
        assertThat((BigDecimal) row(order.id()).get("total_amount")).isEqualByComparingTo(UNIT_PRICE);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT product_id, option_id, quantity, option_title_snapshot FROM order_items WHERE order_id = ?
                """, order.id()))
                .containsEntry("product_id", product.productId())
                .containsEntry("option_id", product.optionId())
                .containsEntry("quantity", 1)
                .containsEntry("option_title_snapshot", OrderFixtures.OPTION_TITLE);
        assertThat(history(order.id())).containsExactly("1:null>AWAITING_PAYMENT:USER");
    }

    // 호출하는 쪽이 제약 이름 문자열에 기대지 않도록 저장소가 도메인 예외로 바꾼다.
    @Test
    void secondOrderForSamePreorderFailsWithDomainException() {
        Long preorderId = preorder();
        ledger.place(draft(preorderId), EventCause.user());

        assertThatThrownBy(() -> ledger.place(draft(preorderId), EventCause.user()))
                .isInstanceOf(OrderAlreadyPlacedException.class)
                .satisfies(e -> assertThat(((OrderAlreadyPlacedException) e).getPreorderId()).isEqualTo(preorderId));
    }

    /*
     * 다른 예약 id 에 이미 쓰인 예약 UUID — uq_order_preorder_token 이 막는다. 저장소는 두 예약 키 중 어느 것이 겹쳐도 같은
     * 도메인 예외로 올린다(여러 키가 겹칠 때 어느 이름으로 알릴지 MySQL 이 보장하지 않으므로 이름으로 가르지 않는다).
     * 짝이 어긋난 것인지는 생성 유스케이스가 예약 id 로 다시 읽어 가른다(PlaceOrderServiceTest).
     */
    @Test
    void preorderTokenOfAnotherPreorderIsRejectedAsPreorderKeyConflict() {
        Long first = preorder();
        ledger.place(draft(first), EventCause.user());
        // 회원당 상품마다 진행 중인 예약은 하나라(uq_preorder_active) 다른 상품의 예약이다.
        PreorderProduct otherProduct = fixtures.preorderProduct();
        Long second = fixtures.payablePreorder(customerId, otherProduct, 1);
        OrderDraft borrowed = preorderCommand(customerId, second, otherProduct).toDraft();
        OrderDraft mismatched = new OrderDraft(borrowed.customerId(), borrowed.source(), second,
                OrderFixtures.preorderToken(first), borrowed.shipTo(), borrowed.lines());

        assertThatThrownBy(() -> ledger.place(mismatched, EventCause.user()))
                .isInstanceOf(OrderAlreadyPlacedException.class)
                .satisfies(e -> assertThat(e).rootCause().hasMessageContaining("uq_order_preorder_token"))
                .satisfies(e -> assertThat(((OrderAlreadyPlacedException) e).getPreorderId()).isEqualTo(second));
    }

    // 앱의 회원 대조를 빠뜨려도 복합 FK(fk_order_preorder)가 한 번 더 막는다. 이건 도메인 예외로 바꾸지 않는다.
    @Test
    void orderForAnotherCustomersPreorderIsRejectedByForeignKey() {
        OrderDraft stranger = preorderCommand(fixtures.customer(), preorder(), product).toDraft();

        assertThatThrownBy(() -> ledger.place(stranger, EventCause.user()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(OrderAlreadyPlacedException.class)
                .hasMessageContaining("fk_order_preorder");
    }

    // 저장소가 쓰기마다 flush 하므로 항목 제약 위반도 부른 자리에서 Spring 예외로 올라온다.
    @Test
    void itemConstraintViolationSurfacesAtCallSiteAsSpringException() {
        OrderDraft missingOption = preorderCommand(customerId, preorder(), product, Long.MAX_VALUE, 1).toDraft();

        assertThatThrownBy(() -> ledger.place(missingOption, EventCause.user()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_order_item_option_ref");
    }

    @Test
    void nonPreorderSourceIsRejectedBeforeWriting() {
        OrderDraft buyNow = new OrderDraft(customerId, OrderSource.BUY_NOW, null, null, draft(1L).shipTo(), draft(1L).lines());

        assertThatThrownBy(() -> ledger.place(buyNow, EventCause.user()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ordersOf(customerId)).isZero();
    }

    @Test
    void cancelRequestCancelsUnpaidOrderAndRecordsReason() {
        Long id = ledger.place(draft(preorder()), EventCause.user()).id();

        OrderTransition result = ledger.fire(id, CANCEL_REQUESTED, UNPAID, EventCause.system("EXPIRY"));

        assertThat(result).isEqualTo(new OrderTransition(true, CANCELED));
        assertThat(row(id)).containsEntry("status", "CANCELED").containsEntry("event_sequence", 2L);
        assertThat(history(id)).containsExactly("1:null>AWAITING_PAYMENT:USER", "2:AWAITING_PAYMENT>CANCELED:SYSTEM");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reason FROM order_events WHERE order_id = ? AND event_sequence = 2", String.class, id))
                .isEqualTo("EXPIRY");
    }

    /*
     * 잠그지 않고 읽은 상태로 사건을 고른 뒤 그사이 결제가 승인된 경우(리뷰 M1).
     * 전제 없이 취소를 적용하면 결제된 주문이 CANCELING(환불)으로 간다. 만료 취소는 이때 전이하지 않아야 한다.
     */
    @Test
    void cancelIsNotAppliedWhenOrderWasPaidAfterCallerDecided() {
        Long id = ledger.place(draft(preorder()), EventCause.user()).id();
        fixtures.forceStatus(id, "AWAITING_CONFIRMATION");

        OrderTransition result = ledger.fire(id, CANCEL_REQUESTED, UNPAID, EventCause.system("EXPIRY"));

        assertThat(result).isEqualTo(new OrderTransition(false, AWAITING_CONFIRMATION));
        assertThat(row(id)).containsEntry("status", "AWAITING_CONFIRMATION").containsEntry("event_sequence", 1L);
        assertThat(history(id)).hasSize(1);
    }

    // 전제를 넓히면 상태 머신이 정한 대로 간다. 전제는 호출하는 쪽의 선택이다.
    @Test
    void sameCancelOnPaidOrderStartsRefundWhenCallerExpectsPaidOrders() {
        Long id = ledger.place(draft(preorder()), EventCause.user()).id();
        fixtures.forceStatus(id, "AWAITING_CONFIRMATION");

        OrderTransition result = ledger.fire(id, CANCEL_REQUESTED, ANY, EventCause.system("USER"));

        assertThat(result).isEqualTo(new OrderTransition(true, OrderStatus.CANCELING));
    }

    @Test
    void sameCancelRequestTwiceIsAppliedOnce() {
        Long id = ledger.place(draft(preorder()), EventCause.user()).id();
        ledger.fire(id, CANCEL_REQUESTED, UNPAID, EventCause.system("USER"));

        OrderTransition again = ledger.fire(id, CANCEL_REQUESTED, UNPAID, EventCause.system("USER"));

        assertThat(again).isEqualTo(new OrderTransition(false, CANCELED));
        assertThat(row(id)).containsEntry("event_sequence", 2L);
        assertThat(history(id)).hasSize(2);
    }

    // 원장은 거절과 중복을 구분하지 않는다. 지금 상태를 돌려주고 판단은 호출하는 쪽이 한다.
    @Test
    void cancelRequestOnShippedOrderChangesNothingAndReportsCurrentStatus() {
        Long id = ledger.place(draft(preorder()), EventCause.user()).id();
        fixtures.forceStatus(id, "SHIPPED");

        OrderTransition result = ledger.fire(id, CANCEL_REQUESTED, ANY, EventCause.system("USER"));

        assertThat(result).isEqualTo(new OrderTransition(false, SHIPPED));
        assertThat(row(id)).containsEntry("status", "SHIPPED").containsEntry("event_sequence", 1L);
        assertThat(history(id)).hasSize(1);
    }

    @Test
    void adminCauseIsRecordedWithReason() {
        Long id = ledger.place(draft(preorder()), EventCause.admin("전화 주문")).id();

        assertThat(history(id)).containsExactly("1:null>AWAITING_PAYMENT:ADMIN");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reason FROM order_events WHERE order_id = ?", String.class, id)).isEqualTo("전화 주문");
    }

    @Test
    void emptyExpectationIsRejected() {
        Long id = ledger.place(draft(preorder()), EventCause.user()).id();

        assertThatThrownBy(() -> ledger.fire(id, CANCEL_REQUESTED, Set.of(), EventCause.user()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void firingOnUnknownOrderFails() {
        assertThatThrownBy(() -> ledger.fire(Long.MAX_VALUE, CANCEL_REQUESTED, ANY, EventCause.system(null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void ledgerRefusesToRunOutsideTransaction() {
        assertThatThrownBy(() -> ledger.fire(1L, CANCEL_REQUESTED, ANY, EventCause.system(null)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> ledger.place(draft(preorder()), EventCause.user()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // 주문 · 항목 · 이력 중 하나만 남지 않는다. 항목 INSERT 가 실패하면 먼저 쓴 주문 행도 없어진다.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedItemInsertLeavesNoOrderBehind() {
        Long preorderId = preorder();
        OrderDraft missingOption = preorderCommand(customerId, preorderId, product, Long.MAX_VALUE, 1).toDraft();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                ledger.place(missingOption, EventCause.user())))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE preorder_id = ?", Integer.class, preorderId)).isZero();
    }

    private Long preorder() {
        return fixtures.payablePreorder(customerId, product, nextPosition.getAndIncrement());
    }

    private OrderDraft draft(Long preorderId) {
        return preorderCommand(customerId, preorderId, product).toDraft();
    }

    private int ordersOf(Long customer) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders WHERE customer_id = ?", Integer.class, customer);
    }

    private Map<String, Object> row(Long id) {
        return jdbcTemplate.queryForMap("""
                SELECT order_token, status, event_sequence, source, preorder_id, total_amount, payment_due_at,
                       ship_to_name, ship_to_line2
                  FROM orders WHERE id = ?
                """, id);
    }

    /** "번호:from>to:actor" 목록. 번호 순. */
    private List<String> history(Long id) {
        return jdbcTemplate.query("""
                SELECT event_sequence, from_status, to_status, actor
                  FROM order_events WHERE order_id = ? ORDER BY event_sequence
                """, (rs, n) -> rs.getLong(1) + ":" + rs.getString(2) + ">" + rs.getString(3) + ":" + rs.getString(4),
                id);
    }
}
