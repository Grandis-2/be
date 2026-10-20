package com.grandis.nova.payment.confirm;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.payment.ClaimedTransaction;
import com.grandis.nova.payment.PaymentErrorCode;
import com.grandis.nova.payment.PaymentLedger;
import com.grandis.nova.payment.client.toss.TossCommandResult;
import com.grandis.nova.payment.client.toss.TossPaymentClient;
import com.grandis.nova.payment.client.toss.UnknownReason;
import com.grandis.nova.payment.domain.exception.LeaseLostException;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.domain.repository.PaymentReader;
import com.grandis.nova.payment.domain.repository.PaymentTransactionReader;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderPaymentKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * DB 로 재현하기 어려운 갈래: 시작 교착(D14) · 첫 승인 요청 결과 지표. 원장은 대역이다(실제 원장 위의 흐름은 PaymentConfirmApiTest).
 */
class ConfirmPaymentServiceTest {

    static final PaymentTarget TARGET = PaymentTarget.order(UUID.fromString("0199a3c4-0000-7000-8000-000000000007"));
    static final Money AMOUNT = Money.won(15000);
    static final ProviderPaymentKey PAYMENT = new ProviderPaymentKey("tgen_service_test");

    PaymentTransactionReader reader = mock(PaymentTransactionReader.class);
    PaymentReader paymentReader = mock(PaymentReader.class);
    PaymentLedger ledger = mock(PaymentLedger.class);
    TossPaymentClient toss = mock(TossPaymentClient.class);
    CaptureSettlement settlement = mock(CaptureSettlement.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    MeterRegistry registry = new SimpleMeterRegistry();
    ConfirmPaymentService service;
    PaymentTransaction seen;

    @BeforeEach
    void setUp() {
        given(transactionManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        service = new ConfirmPaymentService(reader, paymentReader, ledger, toss, settlement,
                new ConfirmOutcomeMetrics(registry), transactionManager);
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

    // 결제사 장애는 거절이 아니라 불명(타임아웃)으로 온다 — 첫 요청의 답을 그 코드로 센다
    @Test
    void countsTheFirstSend() {
        startsAndSends(new TossCommandResult.Unknown(UnknownReason.TIMEOUT, null));

        ConfirmResult result = service.confirm(seen.providerOrderId(), TARGET, PAYMENT, AMOUNT, true);

        assertThat(result).isEqualTo(ConfirmResult.pending());
        assertThat(count("UNKNOWN", "TIMEOUT")).isEqualTo(1);
        assertThat(total()).isEqualTo(1);
    }

    // 리스를 잃어 반영하지 못해도 토스가 첫 요청에 준 답은 그대로다
    @Test
    void countsEvenWhenTheLeaseIsLost() {
        startsAndSends(new TossCommandResult.Rejected("REJECT_CARD_PAYMENT", "한도초과"));
        willThrow(new LeaseLostException(seen.id())).given(settlement).settle(any(), any());

        ConfirmResult result = service.confirm(seen.providerOrderId(), TARGET, PAYMENT, AMOUNT, true);

        assertThat(result).isEqualTo(ConfirmResult.pending());
        assertThat(count("REJECTED", "REJECT_CARD_PAYMENT")).isEqualTo(1);
        assertThat(total()).isEqualTo(1);
    }

    // 이미 시작한 거래(같은 결제창 번호로 다시 부름)는 토스를 부르지 않으므로 세지 않는다
    @Test
    void doesNotCountAnAlreadyStartedTransaction() {
        given(ledger.start(any(), any(), any(), any())).willReturn(Optional.empty());
        given(reader.findById(any())).willReturn(Optional.of(seen));

        service.confirm(seen.providerOrderId(), TARGET, PAYMENT, AMOUNT, true);

        verify(toss, never()).confirm(any(), any());
        assertThat(total()).isZero();
    }

    @Test
    void doesNotCountWhenStartIsNotAllowed() {
        given(reader.findById(any())).willReturn(Optional.of(seen));

        service.confirm(seen.providerOrderId(), TARGET, PAYMENT, AMOUNT, false);

        verify(ledger, never()).start(any(), any(), any(), any());
        verify(toss, never()).confirm(any(), any());
        assertThat(total()).isZero();
    }

    private void startsAndSends(TossCommandResult answer) {
        ClaimedTransaction claimed = mock(ClaimedTransaction.class);
        given(claimed.transaction()).willReturn(seen);
        given(ledger.start(any(), any(), any(), any())).willReturn(Optional.of(claimed));
        given(toss.confirm(any(), any())).willReturn(answer);
    }

    private double count(String outcome, String code) {
        return registry.get(ConfirmOutcomeMetrics.METER).tag("outcome", outcome).tag("code", code).counter().count();
    }

    private double total() {
        return registry.find(ConfirmOutcomeMetrics.METER).counters().stream().mapToDouble(Counter::count).sum();
    }
}
