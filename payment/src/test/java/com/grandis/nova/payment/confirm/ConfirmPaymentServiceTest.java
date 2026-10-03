package com.grandis.nova.payment.confirm;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.payment.PaymentErrorCode;
import com.grandis.nova.payment.PaymentLedger;
import com.grandis.nova.payment.client.toss.TossPaymentClient;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.domain.repository.PaymentReader;
import com.grandis.nova.payment.domain.repository.PaymentTransactionReader;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderPaymentKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * DB 로 재현하기 어려운 갈래: 시작 교착(D14). 원장은 대역이다(실제 원장 위의 흐름은 PaymentConfirmApiTest).
 */
class ConfirmPaymentServiceTest {

    static final PaymentTarget TARGET = PaymentTarget.order(7L);
    static final Money AMOUNT = Money.won(15000);
    static final ProviderPaymentKey PAYMENT = new ProviderPaymentKey("tgen_service_test");

    PaymentTransactionReader reader = mock(PaymentTransactionReader.class);
    PaymentReader paymentReader = mock(PaymentReader.class);
    PaymentLedger ledger = mock(PaymentLedger.class);
    TossPaymentClient toss = mock(TossPaymentClient.class);
    CaptureSettlement settlement = mock(CaptureSettlement.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    ConfirmPaymentService service;
    PaymentTransaction seen;

    @BeforeEach
    void setUp() {
        given(transactionManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        service = new ConfirmPaymentService(reader, paymentReader, ledger, toss, settlement, transactionManager);
        seen = PaymentTransaction.openCapture(TARGET, AMOUNT, Instant.parse("2026-10-02T00:00:00Z"));
        given(reader.findByProviderOrderId(seen.providerOrderId())).willReturn(Optional.of(seen));
    }

    // 교착은 돈에 영향이 없다 — 한 번 다시 한다. 다시 해서 "이미 시작됨"이면 지금 상태를 돌려준다
    @Test
    void retriesStartOnceAfterDeadlock() {
        given(ledger.start(any(), any(), any(), any()))
                .willThrow(new CannotAcquireLockException("deadlock"))
                .willReturn(Optional.empty());
        given(reader.findById(any())).willReturn(Optional.of(seen));

        ConfirmResult result = service.confirm(seen.providerOrderId(), TARGET, PAYMENT, AMOUNT, true);

        assertThat(result).isEqualTo(ConfirmResult.pending());
        verify(ledger, times(2)).start(any(), any(), any(), any());
        verify(toss, never()).confirm(any(), any());
    }

    @Test
    void repeatedDeadlockIsNotStarted() {
        given(ledger.start(any(), any(), any(), any())).willThrow(new CannotAcquireLockException("deadlock"));

        assertThatThrownBy(() -> service.confirm(seen.providerOrderId(), TARGET, PAYMENT, AMOUNT, true))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(PaymentErrorCode.PAYMENT_START_CONFLICT));
        verify(toss, never()).confirm(any(), any());
    }

    @Test
    void refusesToRunInsideTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.confirm(seen.providerOrderId(), TARGET, PAYMENT, AMOUNT, true))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
