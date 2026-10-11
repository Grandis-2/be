package com.grandis.nova.order.order.pay;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.order.MySqlLockFailures;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.enums.OrderTrigger;
import com.grandis.nova.order.order.domain.model.OrderTransition;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.support.TestIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 결제 결과 반영의 잠금 실패 가르기 — 교착만 새 트랜잭션에서 다시(최대 3), 잠금 대기 초과는 다시 하지 않고, 다 써도 503 이다.
 * 바깥 트랜잭션 안이면 새 트랜잭션을 열 수 없으므로 다시 하지 않는다. 진짜 교착의 반영은 CartOrderPaymentTest 가 MySQL 로 본다.
 */
class PaymentResultsRetryTest {

    static final UUID ORDER = TestIds.id(1);
    static final String ATTEMPT = "p-1";

    final OrderLedger ledger = mock(OrderLedger.class);
    final PaymentResults results = new PaymentResults(ledger, mock(OrderReader.class), mock(CartOrderFulfillment.class),
            mock(PlatformTransactionManager.class));

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void deadlockIsRetriedAndExhaustedDeadlockIsUnavailable() {
        given(ledger.settlePayment(eq(ORDER), eq(OrderTrigger.PAYMENT_DECLINED), eq(ATTEMPT), any()))
                .willThrow(lockFailure(MySqlLockFailures.MYSQL_DEADLOCK))
                .willReturn(new OrderTransition(true, OrderStatus.AWAITING_PAYMENT));
        assertThat(results.notStarted(ORDER, ATTEMPT).applied()).isTrue();

        given(ledger.settlePayment(eq(ORDER), eq(OrderTrigger.PAYMENT_APPROVED), eq(ATTEMPT), any()))
                .willThrow(lockFailure(MySqlLockFailures.MYSQL_DEADLOCK));
        assertThatThrownBy(() -> results.approved(ORDER, ATTEMPT)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
        verify(ledger, times(PaymentResults.MAX_ATTEMPTS)).settlePayment(eq(ORDER), eq(OrderTrigger.PAYMENT_APPROVED), eq(ATTEMPT), any());
    }

    @Test
    void lockWaitTimeoutIsNotRetried() {
        given(ledger.settlePayment(eq(ORDER), eq(OrderTrigger.PAYMENT_APPROVED), eq(ATTEMPT), any())).willThrow(lockFailure(1205));

        assertThatThrownBy(() -> results.approved(ORDER, ATTEMPT)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
        verify(ledger, times(1)).settlePayment(eq(ORDER), eq(OrderTrigger.PAYMENT_APPROVED), eq(ATTEMPT), any());
    }

    @Test
    void insideOuterTransactionDeadlockIsNotRetried() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        given(ledger.settlePayment(eq(ORDER), eq(OrderTrigger.PAYMENT_APPROVED), eq(ATTEMPT), any()))
                .willThrow(lockFailure(MySqlLockFailures.MYSQL_DEADLOCK));

        assertThatThrownBy(() -> results.approved(ORDER, ATTEMPT)).isInstanceOf(BusinessException.class);
        verify(ledger, times(1)).settlePayment(eq(ORDER), eq(OrderTrigger.PAYMENT_APPROVED), eq(ATTEMPT), any());
    }

    private static CannotAcquireLockException lockFailure(int vendorCode) {
        return new CannotAcquireLockException("lock", new SQLException("lock failure", "40001", vendorCode));
    }
}
