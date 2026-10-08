package com.grandis.nova.order.order.domain.model;

import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.vo.Money;
import com.grandis.nova.order.order.vo.OrderToken;
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
        OrderToken token = OrderToken.issue();

        Order order = Order.place(new OrderDraft(TestIds.id(1), OrderSource.PREORDER, TestIds.id(7), PREORDER_UUID, SHIP_TO,
                List.of(line(TestIds.id(10), 1, 1_250_000))), token);

        assertThat(order.id()).isNull();
        assertThat(order.orderToken()).isEqualTo(token);
        assertThat(order.status()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(order.eventSequence()).isEqualTo(OrderEvent.FIRST_SEQUENCE);
        assertThat(order.totalAmount()).isEqualTo(Money.won(1_250_000));
        assertThat(order.paymentDueAt()).isNull();
        assertThat(order.preorderToken()).isEqualTo(PREORDER_UUID);
    }

    @Test
    void onlyPreorderOrdersCanBePlacedForNow() {
        OrderDraft buyNow = new OrderDraft(TestIds.id(1), OrderSource.BUY_NOW, null, null, SHIP_TO, List.of(line(TestIds.id(10), 1, 1000)));

        assertThatThrownBy(() -> Order.place(buyNow, OrderToken.issue()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BUY_NOW");
    }

    @Test
    void preorderOrderHasExactlyOneUnitOfOneOption() {
        OrderDraft twoUnits = new OrderDraft(TestIds.id(1), OrderSource.PREORDER, TestIds.id(7), PREORDER_UUID, SHIP_TO, List.of(line(TestIds.id(10), 2, 1000)));
        OrderDraft twoOptions = new OrderDraft(TestIds.id(1), OrderSource.PREORDER, TestIds.id(7), PREORDER_UUID, SHIP_TO,
                List.of(line(TestIds.id(10), 1, 1000), line(TestIds.id(11), 1, 1000)));

        assertThatThrownBy(() -> Order.place(twoUnits, OrderToken.issue())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Order.place(twoOptions, OrderToken.issue())).isInstanceOf(IllegalArgumentException.class);
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

    // 되살린 행에도 ck_order_preorder_token 규칙을 건다.
    @Test
    void constructorRejectsPreorderOrderWithoutPreorderToken() {
        assertThatThrownBy(() -> new Order(TestIds.id(1), TOKEN, TestIds.id(1), OrderSource.PREORDER, TestIds.id(7), null, OrderStatus.AWAITING_PAYMENT, null,
                Money.won(1000), null, null, SHIP_TO, null, 1, Instant.EPOCH, Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preorderToken");
    }

    @Test
    void constructorRejectsEventSequenceBelowOne() {
        assertThatThrownBy(() -> order(OrderSource.PREORDER, TestIds.id(7), OrderStatus.AWAITING_PAYMENT, null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 배송지 · 관리자 메모에는 개인정보가 들어갈 수 있다. 로그 · 예외 메시지로 새지 않게 식별 · 상태만 싣는다.
    @Test
    void toStringCarriesNoPersonalData() {
        Order order = new Order(TestIds.id(1), TOKEN, TestIds.id(1), OrderSource.PREORDER, TestIds.id(7), PREORDER_UUID, OrderStatus.AWAITING_PAYMENT, null,
                Money.won(1000), null, null, SHIP_TO, "고객 요청: 010-9999-8888 로 연락", 1, Instant.EPOCH, Instant.EPOCH);

        assertThat(order.toString())
                .contains("status=AWAITING_PAYMENT")
                .doesNotContain("홍길동", "010-0000-0000", "세종대로", "010-9999-8888");
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
        assertThatThrownBy(() -> new Order(TestIds.id(1), TOKEN, TestIds.id(1), OrderSource.PREORDER, TestIds.id(7), PREORDER_UUID,
                OrderStatus.AWAITING_PAYMENT, "attempt-0001", Money.won(1000), null, null, SHIP_TO, null, 1,
                Instant.EPOCH, Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Order(TestIds.id(1), TOKEN, TestIds.id(1), OrderSource.PREORDER, TestIds.id(7), PREORDER_UUID, OrderStatus.AUTHORIZING,
                "attempt-0001", Money.won(1000), null, null, SHIP_TO, null, 2, Instant.EPOCH, Instant.EPOCH)
                .authorizingProviderOrderId()).isEqualTo("attempt-0001");
    }

    private static final OrderToken TOKEN = OrderToken.issue();

    private static Order order(OrderSource source, UUID preorderId, OrderStatus status, Instant paymentDueAt,
                               Instant stockReleasedAt, long eventSequence) {
        String preorderToken = source == OrderSource.PREORDER ? PREORDER_UUID : null;
        return new Order(TestIds.id(1), TOKEN, TestIds.id(1), source, preorderId, preorderToken, status, null, Money.won(1000), paymentDueAt, stockReleasedAt,
                SHIP_TO, null, eventSequence, Instant.EPOCH, Instant.EPOCH);
    }
}
