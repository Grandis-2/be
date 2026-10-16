package com.grandis.nova.order.order.domain.model;

import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.vo.Money;
import com.grandis.nova.order.order.vo.Quantity;
import com.grandis.nova.order.support.TestIds;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.grandis.nova.order.order.domain.model.OrderDraftTest.PREORDER_UUID;
import static com.grandis.nova.order.order.domain.model.OrderDraftTest.SHIP_TO;
import static com.grandis.nova.order.order.domain.model.OrderDraftTest.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    @Test
    void newPreorderOrderAwaitsPaymentWithFirstSequenceAndNoDueDate() {
        Order order = Order.place(new OrderDraft(TestIds.id(1), OrderSource.PREORDER, TestIds.id(7), PREORDER_UUID, null, SHIP_TO,
                List.of(line(TestIds.id(10), 1, 1_250_000))), Instant.EPOCH);

        assertThat(order.id()).isNull();
        assertThat(order.status()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(order.eventSequence()).isEqualTo(OrderEvent.FIRST_SEQUENCE);
        assertThat(order.totalAmount()).isEqualTo(Money.won(1_250_000));
        assertThat(order.paymentDueAt()).isNull();
        assertThat(order.preorderToken()).isEqualTo(PREORDER_UUID);
    }

    @Test
    void buyNowOrderHasOneOptionAndPaymentWindow() {
        OrderDraft buyNow = new OrderDraft(TestIds.id(1), OrderSource.BUY_NOW, null, null, TestIds.id(50), SHIP_TO, List.of(line(TestIds.id(10), 2, 1000)));

        Order order = Order.place(buyNow, Instant.EPOCH);

        assertThat(order.status()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(order.paymentDueAt()).isEqualTo(Instant.EPOCH.plus(Order.PAYMENT_WINDOW));
        OrderDraft twoOptions = new OrderDraft(TestIds.id(1), OrderSource.BUY_NOW, null, null, TestIds.id(50), SHIP_TO,
                List.of(line(TestIds.id(10), 1, 1000), line(TestIds.id(11), 1, 1000)));
        assertThatThrownBy(() -> Order.place(twoOptions, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("옵션 하나");
    }

    @Test
    void preorderOrderHasExactlyOneUnitOfOneOption() {
        OrderDraft twoUnits = new OrderDraft(TestIds.id(1), OrderSource.PREORDER, TestIds.id(7), PREORDER_UUID, null, SHIP_TO, List.of(line(TestIds.id(10), 2, 1000)));
        OrderDraft twoOptions = new OrderDraft(TestIds.id(1), OrderSource.PREORDER, TestIds.id(7), PREORDER_UUID, null, SHIP_TO,
                List.of(line(TestIds.id(10), 1, 1000), line(TestIds.id(11), 1, 1000)));

        assertThatThrownBy(() -> Order.place(twoUnits, Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Order.place(twoOptions, Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
    }

    // 생성자는 place 를 거치지 않는 경로(저장소에서 되살리기 등)에도 DB CHECK 와 같은 규칙을 건다.
    @Test
    void constructorRejectsPreorderOrderWithPaymentDueDate() {
        assertThatThrownBy(() -> order(OrderSource.PREORDER, TestIds.id(7), OrderStatus.AWAITING_PAYMENT, Instant.EPOCH, null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsGeneralOrderWithoutPaymentDueDateOrWithPreorderId() {
        assertThatThrownBy(() -> order(OrderSource.BUY_NOW, null, OrderStatus.AWAITING_PAYMENT, null, null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order(OrderSource.BUY_NOW, TestIds.id(7), OrderStatus.AWAITING_PAYMENT, Instant.EPOCH, null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stockReleasedMarkOnlyOnCanceledGeneralOrder() {
        assertThat(order(OrderSource.BUY_NOW, null, OrderStatus.CANCELED, Instant.EPOCH, Instant.EPOCH, 2)).isNotNull();
        assertThatThrownBy(() -> order(OrderSource.BUY_NOW, null, OrderStatus.AWAITING_PAYMENT, Instant.EPOCH,
                Instant.EPOCH, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order(OrderSource.PREORDER, TestIds.id(7), OrderStatus.CANCELED, null, Instant.EPOCH, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refundFailedAtOnlyWhileCancelingOrAfterCanceled() {
        assertThat(refundFailed(OrderStatus.CANCELING)).isNotNull();
        assertThat(refundFailed(OrderStatus.CANCELED)).isNotNull();
        assertThatThrownBy(() -> refundFailed(OrderStatus.AWAITING_CONFIRMATION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 읽은 사전예약 주문은 예약 UUID 가 비어도 받는다(이 칸을 채우지 않는 버전과 같이 돈다). 있으면 사전예약 주문의 36자여야 한다.
    @Test
    void constructorAcceptsPreorderOrderWithoutPreorderTokenButChecksPresentOne() {
        assertThat(new Order(TestIds.id(1), TestIds.id(1), OrderSource.PREORDER, TestIds.id(7), null, null, OrderStatus.AWAITING_PAYMENT, null,
                Money.won(1000), null, null, null, SHIP_TO, 1, Instant.EPOCH, Instant.EPOCH).preorderToken()).isNull();
        assertThatThrownBy(() -> new Order(TestIds.id(1), TestIds.id(1), OrderSource.PREORDER, TestIds.id(7), "0b8f6a3e", null,
                OrderStatus.AWAITING_PAYMENT, null, Money.won(1000), null, null, null, SHIP_TO, 1, Instant.EPOCH, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("36");
        assertThatThrownBy(() -> new Order(TestIds.id(1), TestIds.id(1), OrderSource.CART, null, PREORDER_UUID, null,
                OrderStatus.AWAITING_PAYMENT, null, Money.won(1000), Instant.EPOCH, null, null, SHIP_TO, 1, Instant.EPOCH, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preorderToken");
    }

    @Test
    void constructorRejectsEventSequenceBelowOne() {
        assertThatThrownBy(() -> order(OrderSource.PREORDER, TestIds.id(7), OrderStatus.AWAITING_PAYMENT, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 배송지에는 개인정보가 들어 있다. 로그 · 예외 메시지로 새지 않게 식별 · 상태만 싣는다.
    @Test
    void toStringCarriesNoPersonalData() {
        Order order = new Order(TestIds.id(1), TestIds.id(1), OrderSource.PREORDER, TestIds.id(7), PREORDER_UUID, null, OrderStatus.AWAITING_PAYMENT, null,
                Money.won(1000), null, null, null, SHIP_TO, 1, Instant.EPOCH, Instant.EPOCH);

        assertThat(order.toString())
                .contains("status=AWAITING_PAYMENT")
                .doesNotContain("홍길동", "010-0000-0000", "세종대로");
    }

    // 값 비교다. 같은 주문의 전이 전 · 후 스냅샷은 다르다.
    @Test
    void equalityIsByValue() {
        Order before = order(OrderSource.PREORDER, TestIds.id(7), OrderStatus.AWAITING_PAYMENT, null, null, 1);
        Order after = order(OrderSource.PREORDER, TestIds.id(7), OrderStatus.CANCELED, null, null, 2);

        assertThat(before).isEqualTo(order(OrderSource.PREORDER, TestIds.id(7), OrderStatus.AWAITING_PAYMENT, null, null, 1));
        assertThat(before).isNotEqualTo(after);
        assertThat(before.id()).isEqualTo(after.id());
    }

    // ck_order_authorizing_attempt: 승인 중일 때만 결제창 번호가 있다
    @Test
    void onlyAuthorizingOrderCarriesAttempt() {
        assertThatThrownBy(() -> order(OrderSource.PREORDER, TestIds.id(7), OrderStatus.AUTHORIZING, null, null, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Order(TestIds.id(1), TestIds.id(1), OrderSource.PREORDER, TestIds.id(7), PREORDER_UUID, null,
                OrderStatus.AWAITING_PAYMENT, "attempt-0001", Money.won(1000), null, null, null, SHIP_TO, 1,
                Instant.EPOCH, Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Order(TestIds.id(1), TestIds.id(1), OrderSource.PREORDER, TestIds.id(7), PREORDER_UUID, null, OrderStatus.AUTHORIZING,
                "attempt-0001", Money.won(1000), null, null, null, SHIP_TO, 2, Instant.EPOCH, Instant.EPOCH)
                .authorizingProviderOrderId()).isEqualTo("attempt-0001");
    }

    private static Order order(OrderSource source, UUID preorderId, OrderStatus status, Instant paymentDueAt,
                               Instant stockReleasedAt, long eventSequence) {
        String preorderToken = source == OrderSource.PREORDER ? PREORDER_UUID : null;
        UUID idempotencyKey = source == OrderSource.BUY_NOW ? TestIds.id(50) : null;
        return new Order(TestIds.id(1), TestIds.id(1), source, preorderId, preorderToken, idempotencyKey, status, null, Money.won(1000), paymentDueAt, stockReleasedAt, null,
                SHIP_TO, eventSequence, Instant.EPOCH, Instant.EPOCH);
    }

    private static Order refundFailed(OrderStatus status) {
        return new Order(TestIds.id(1), TestIds.id(1), OrderSource.PREORDER, TestIds.id(7), PREORDER_UUID, null, status, null,
                Money.won(1000), null, null, Instant.EPOCH, SHIP_TO, 3, Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void cartOrderHasPaymentWindowAndManyLinesWithWarranty() {
        Instant now = Instant.parse("2026-10-10T06:00:00Z");
        OrderDraft cart = new OrderDraft(TestIds.id(1), OrderSource.CART, null, null, null, SHIP_TO, List.of(
                new OrderLine(TestIds.id(100), TestIds.id(10), new Quantity(2), Money.won(1_000), 1, Money.won(300), "상품", "옵션 1"),
                line(TestIds.id(11), 3, 500)));

        Order order = Order.place(cart, now);

        assertThat(order.paymentDueAt()).isEqualTo(now.plus(Order.PAYMENT_WINDOW)).isEqualTo(Instant.parse("2026-10-10T06:10:00Z"));
        assertThat(order.totalAmount()).isEqualTo(Money.won(2 * 1_000 + 300 + 3 * 500));
        assertThat(order.status()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(order.preorderId()).isNull();
    }

    @Test
    void preorderOrderCannotCarryWarranty() {
        OrderDraft withWarranty = new OrderDraft(TestIds.id(1), OrderSource.PREORDER, TestIds.id(7), PREORDER_UUID, null, SHIP_TO, List.of(
                new OrderLine(TestIds.id(100), TestIds.id(10), Quantity.ONE, Money.won(1_000), 1, Money.won(300), "상품", "옵션")));

        assertThatThrownBy(() -> Order.place(withWarranty, Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 기한 == now 는 지났다 — 기한이 지난 주문을 고르는 쪽(만료 처리)은 기한 <= now 를 써야 사이에 빠지는 주문이 없다(계약). */
    @Test
    void cartOrderAcceptsPaymentStrictlyBeforeDue() {
        Instant now = Instant.parse("2026-10-10T06:00:00Z");
        Order order = Order.place(new OrderDraft(TestIds.id(1), OrderSource.CART, null, null, null, SHIP_TO, List.of(line(TestIds.id(10), 1, 1000))),
                now);
        Instant due = now.plus(Order.PAYMENT_WINDOW);

        assertThat(order.acceptsPaymentAt(due.minusNanos(1))).isTrue();
        assertThat(order.acceptsPaymentAt(due)).isFalse();
        assertThatThrownBy(() -> Order.place(new OrderDraft(TestIds.id(1), OrderSource.PREORDER, TestIds.id(7), PREORDER_UUID, null, SHIP_TO,
                List.of(line(TestIds.id(10), 1, 1000))), now).acceptsPaymentAt(now))
                .isInstanceOf(IllegalStateException.class);
    }

}
