package com.grandis.nova.order.order.place;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.client.preorder.PreorderPayability;
import com.grandis.nova.order.client.preorder.PreorderReader;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.exception.OrderAlreadyPlacedException;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderDraft;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.vo.Money;
import com.grandis.nova.order.order.vo.OrderToken;
import com.grandis.nova.order.order.vo.ShipTo;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.TestIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 재시도 · preorder 판정 사유처럼 DB 로 일으키기 어려운 분기. 원장 · 저장소 · preorder 는 대역이다. */
class PlaceOrderServiceTest {

    static final Instant NOW = Instant.parse("2026-09-25T00:00:00Z");
    static final String PREORDER_UUID = "0b8f6a3e-5a8c-4d59-9a53-3c1f0e0f7a11";
    static final UUID CUSTOMER_ID = TestIds.id(7);
    static final UUID PREORDER_ID = TestIds.id(11);
    // 전달할 세션 토큰 자리. 실제 토큰 모양이 아니다.
    static final String SESSION = "service-test-user-7";

    PreorderReader preorderReader = mock(PreorderReader.class);
    OrderLedger ledger = mock(OrderLedger.class);
    OrderReader orderReader = mock(OrderReader.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    PlaceOrderService service = new PlaceOrderService(preorderReader, ledger, orderReader, transactionManager);

    @BeforeEach
    void setUp() {
        given(transactionManager.getTransaction(any())).willAnswer(invocation -> new SimpleTransactionStatus());
        given(orderReader.findByPreorderId(PREORDER_ID)).willReturn(Optional.empty());
        given(orderReader.findItems(any())).willReturn(List.of());
    }

    @Test
    void deadlockIsRetried() {
        payable();
        given(ledger.place(any(), any()))
                .willThrow(new CannotAcquireLockException("Deadlock found"))
                .willReturn(order(OrderStatus.AWAITING_PAYMENT));

        PlaceResult result = service.place(CUSTOMER_ID, SESSION, PREORDER_UUID, OrderFixtures.ADDRESS);

        assertThat(result.created()).isTrue();
        verify(ledger, times(2)).place(any(), any());
    }

    // 예약 내부 id 와 공개 UUID 는 같은 응답에서 함께 옮긴다 — 짝을 DB 가 아니라 이것이 보장한다(orders.preorder_token).
    @Test
    void draftCarriesPreorderIdAndTokenFromTheSameResponse() {
        payable();
        given(ledger.place(any(), any())).willReturn(order(OrderStatus.AWAITING_PAYMENT));

        service.place(CUSTOMER_ID, SESSION, PREORDER_UUID, OrderFixtures.ADDRESS);

        ArgumentCaptor<OrderDraft> draft = ArgumentCaptor.forClass(OrderDraft.class);
        verify(ledger).place(draft.capture(), any());
        assertThat(draft.getValue().preorderId()).isEqualTo(PREORDER_ID);
        assertThat(draft.getValue().preorderToken()).isEqualTo(PREORDER_UUID);
    }

    @Test
    void deadlockGivesUpAfterMaxAttempts() {
        payable();
        given(ledger.place(any(), any())).willThrow(new CannotAcquireLockException("Deadlock found"));

        assertThatThrownBy(() -> service.place(CUSTOMER_ID, SESSION, PREORDER_UUID, OrderFixtures.ADDRESS))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
        verify(ledger, times(PlaceOrderService.MAX_ATTEMPTS)).place(any(), any());
    }

    // 바깥 트랜잭션에 참여하면 원장 예외 뒤의 재시도 · 재조회가 rollback-only 인 같은 트랜잭션에서 일어난다.
    @Test
    void refusesToRunInsideTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.place(CUSTOMER_ID, SESSION, PREORDER_UUID, OrderFixtures.ADDRESS))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(preorderReader, never()).find(any(), any());
    }

    // 예약 스냅샷의 본인 확인과 별개로, 돌려줄 주문의 회원도 대조한다.
    @Test
    void existingOrderOfAnotherCustomerIsHidden() {
        payable();
        Order foreign = new Order(TestIds.id(100), OrderToken.issue(), TestIds.id(999), OrderSource.PREORDER, PREORDER_ID, PREORDER_UUID,
                OrderStatus.AWAITING_PAYMENT, null, Money.won(1_250_000), null, null,
                new ShipTo("홍길동", "010-0000-0000", "04524", "서울시 중구 세종대로 110", null), null, 1, NOW, NOW);
        given(orderReader.findByPreorderId(PREORDER_ID)).willReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.place(CUSTOMER_ID, SESSION, PREORDER_UUID, OrderFixtures.ADDRESS))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(OrderErrorCode.PREORDER_NOT_FOUND));
    }

    // 먼저 확인할 때는 없었는데 그사이 다른 요청이 커밋했다. 새로 읽은 주문을 돌려준다.
    @Test
    void duplicateKeyReturnsOrderCommittedMeanwhile() {
        payable();
        Order committed = order(OrderStatus.AWAITING_PAYMENT);
        given(orderReader.findByPreorderId(PREORDER_ID)).willReturn(Optional.empty(), Optional.of(committed));
        given(ledger.place(any(), any())).willThrow(new OrderAlreadyPlacedException(PREORDER_ID, null));

        PlaceResult result = service.place(CUSTOMER_ID, SESSION, PREORDER_UUID, OrderFixtures.ADDRESS);

        assertThat(result.created()).isFalse();
        assertThat(result.order()).isEqualTo(committed);
    }

    /*
     * 예약 식별 키가 겹쳤는데 이 예약 id 의 주문이 없다 — 다른 예약이 같은 UUID 를 쓰고 있다(짝 어긋남).
     * 두 키 중 어느 이름으로 알렸든 저장소는 같은 예외를 올리므로, 여기서 다시 읽어 가른다. 기존 주문으로 숨기지 않는다.
     */
    @Test
    void duplicateKeyWithoutOrderOfThisPreorderIsDataInconsistency() {
        payable();
        given(ledger.place(any(), any())).willThrow(new OrderAlreadyPlacedException(PREORDER_ID, null));

        assertThatThrownBy(() -> service.place(CUSTOMER_ID, SESSION, PREORDER_UUID, OrderFixtures.ADDRESS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이 예약의 주문이 없다");
    }

    // 그 예약 id 의 주문이 있어도 예약 UUID 가 다르면 짝이 어긋난 주문이다 — "이 예약의 주문" 으로 돌려주지 않는다.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void existingOrderWithAnotherPreorderTokenIsNotReturned(boolean foundAfterDuplicateKey) {
        payable();
        Order mismatched = new Order(TestIds.id(100), OrderToken.issue(), CUSTOMER_ID, OrderSource.PREORDER, PREORDER_ID,
                "1c9e2b7d-3f4a-4b5c-8d6e-7f8091a2b3c4", OrderStatus.AWAITING_PAYMENT, null, Money.won(1_250_000), null, null,
                new ShipTo("홍길동", "010-0000-0000", "04524", "서울시 중구 세종대로 110", null), null, 1, NOW, NOW);
        if (foundAfterDuplicateKey) {
            given(orderReader.findByPreorderId(PREORDER_ID)).willReturn(Optional.empty(), Optional.of(mismatched));
            given(ledger.place(any(), any())).willThrow(new OrderAlreadyPlacedException(PREORDER_ID, null));
        } else {
            given(orderReader.findByPreorderId(PREORDER_ID)).willReturn(Optional.of(mismatched));
        }

        assertThatThrownBy(() -> service.place(CUSTOMER_ID, SESSION, PREORDER_UUID, OrderFixtures.ADDRESS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("예약 UUID 가 예약과 다르다");
    }

    // 받은 토큰을 preorder 호출까지 그대로 넘긴다.
    @Test
    void forwardsSessionTokenToPreorder() {
        payable();
        given(ledger.place(any(), any())).willReturn(order(OrderStatus.AWAITING_PAYMENT));

        service.place(CUSTOMER_ID, SESSION, PREORDER_UUID, OrderFixtures.ADDRESS);

        verify(preorderReader).find(PREORDER_UUID, SESSION);
    }

    // 기한 판정은 preorder 몫이다. order 는 사유만 보고 다시 계산하지 않는다.
    @Test
    void duePassedIsPaymentWindowExpired() {
        blocked("PAYABLE", "DUE_PASSED");

        assertThatThrownBy(() -> service.place(CUSTOMER_ID, SESSION, PREORDER_UUID, OrderFixtures.ADDRESS))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(OrderErrorCode.PAYMENT_WINDOW_EXPIRED));
        verify(ledger, never()).place(any(), any());
    }

    // 모르는 사유도 결제할 수 없는 예약으로 본다 — preorder 가 사유를 늘려도 주문을 만들지 않는 쪽으로 떨어진다.
    @ParameterizedTest
    @ValueSource(strings = {"NOT_YET_REGISTERED", "CANCELING", "CANCELED", "SOMETHING_NEW"})
    void otherReasonsAreNotPayable(String reason) {
        blocked("CANCELED", reason);

        assertThatThrownBy(() -> service.place(CUSTOMER_ID, SESSION, PREORDER_UUID, OrderFixtures.ADDRESS))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(OrderErrorCode.PREORDER_NOT_PAYABLE));
        verify(ledger, never()).place(any(), any());
    }

    // preorder 가 payable 이라 하면 order 는 상태 · 기한을 다시 보지 않는다(두 벌 규칙 없음).
    @Test
    void payableIsTrustedWithoutRecheckingWindow() {
        given(preorderReader.find(PREORDER_UUID, SESSION)).willReturn(Optional.of(payability(
                NOW.minus(Duration.ofHours(30)), NOW.minus(Duration.ofHours(6)), true, null)));
        given(ledger.place(any(), any())).willReturn(order(OrderStatus.AWAITING_PAYMENT));

        assertThat(service.place(CUSTOMER_ID, SESSION, PREORDER_UUID, OrderFixtures.ADDRESS).created()).isTrue();
    }

    // 단가는 원 단위 정수다. preorder 가 소수 단가를 보내면 계약 위반이라 주문을 만들지 않는다(Money 가 거부, 공통 처리기에서 500).
    @Test
    void fractionalUnitPriceFromPreorderIsRejectedBeforeTransaction() {
        Instant payableFrom = NOW.minus(Duration.ofHours(1));
        given(preorderReader.find(PREORDER_UUID, SESSION)).willReturn(Optional.of(new PreorderPayability(PREORDER_UUID,
                PREORDER_ID, CUSTOMER_ID, TestIds.id(3), TestIds.id(30), "Nova 1", "블랙 / 256GB", new BigDecimal("1250000.5"), "PAYABLE",
                payableFrom, payableFrom.plus(Duration.ofHours(24)), true, null)));

        assertThatThrownBy(() -> service.place(CUSTOMER_ID, SESSION, PREORDER_UUID, OrderFixtures.ADDRESS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("원 단위");
        verify(ledger, never()).place(any(), any());
        verify(transactionManager, times(1)).getTransaction(any());   // 기존 주문 확인(읽기)만 열렸다
    }

    private void payable() {
        Instant payableFrom = NOW.minus(Duration.ofHours(1));
        given(preorderReader.find(PREORDER_UUID, SESSION)).willReturn(Optional.of(
                payability(payableFrom, payableFrom.plus(Duration.ofHours(24)), true, null)));
    }

    private void blocked(String status, String reason) {
        given(preorderReader.find(PREORDER_UUID, SESSION)).willReturn(Optional.of(new PreorderPayability(PREORDER_UUID,
                PREORDER_ID, CUSTOMER_ID, TestIds.id(3), TestIds.id(30), "Nova 1", "블랙 / 256GB", new BigDecimal("1250000"), status,
                NOW.minus(Duration.ofHours(25)), NOW.minus(Duration.ofHours(1)), false, reason)));
    }

    private static PreorderPayability payability(Instant payableFrom, Instant paymentDueAt, boolean payable,
                                                 String reason) {
        return new PreorderPayability(PREORDER_UUID, PREORDER_ID, CUSTOMER_ID, TestIds.id(3), TestIds.id(30), "Nova 1", "블랙 / 256GB",
                new BigDecimal("1250000"), "PAYABLE", payableFrom, paymentDueAt, payable, reason);
    }

    private static Order order(OrderStatus status) {
        return new Order(TestIds.id(100), OrderToken.issue(), CUSTOMER_ID, OrderSource.PREORDER, PREORDER_ID, PREORDER_UUID, status, null,
                Money.won(1_250_000), null, null,
                new ShipTo("홍길동", "010-0000-0000", "04524", "서울시 중구 세종대로 110", null), null, 1, NOW, NOW);
    }
}
