package com.grandis.nova.payment.event;

import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.payment.ClaimedTransaction;
import com.grandis.nova.payment.PaymentLedger;
import com.grandis.nova.payment.domain.enums.TransactionStatus;
import com.grandis.nova.payment.domain.enums.TransactionType;
import com.grandis.nova.payment.domain.model.Outcome;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.domain.repository.PaymentTransactionReader;
import com.grandis.nova.payment.support.PaymentFixtures;
import com.grandis.nova.payment.support.PaymentIntegrationTest;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderError;
import com.grandis.nova.payment.vo.ProviderPaymentKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 환불 요청 수신(ORDER_REFUND_REQUESTED). 요청은 최소 1회 전달이라 같은 요청이 다시 온다 — 대상당 REFUND 는 하나만 열리고,
 * 이미 진행 중 · 이미 환불 · 확정 실패 뒤의 요청은 아무것도 열지 않고 성공으로 끝나야 한다(소비기가 지운다).
 */
@PaymentIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class PaymentEventDispatcherTest {

    static final Money AMOUNT = Money.won(15000);
    static final Instant APPROVED_AT = Instant.parse("2026-10-04T01:00:00Z");

    @Autowired
    PaymentEventDispatcher dispatcher;

    @Autowired
    PaymentLedger ledger;

    @Autowired
    PaymentTransactionReader transactions;

    @Autowired
    TransactionTemplate transactionTemplate;

    @MockitoBean
    RevocationChecker revocationChecker;

    PaymentTarget target;
    ProviderPaymentKey paymentKey;

    @BeforeEach
    void setUp() {
        target = PaymentFixtures.newOrderTarget();
        paymentKey = PaymentFixtures.newProviderPayment();
    }

    @Test
    void refundRequestOpensRefundOfStoredPayment() {
        paid();

        dispatcher.dispatch(refundRequested(target.id(), "15000"));

        assertThat(refunds()).singleElement().satisfies(refund -> {
            assertThat(refund.status()).isEqualTo(TransactionStatus.PENDING);
            assertThat(refund.providerPaymentKey()).isEqualTo(paymentKey);
            assertThat(refund.amount()).isEqualTo(AMOUNT);
        });
    }

    @Test
    void repeatedRequestDoesNotOpenSecondRefund() {
        paid();

        dispatcher.dispatch(refundRequested(target.id(), "15000"));
        dispatcher.dispatch(refundRequested(target.id(), "15000"));

        assertThat(refunds()).hasSize(1);
    }

    @Test
    void requestAfterRefundCompletedIsAcceptedWithoutOpening() {
        paid();
        resolveRefund(new Outcome.Confirmed(APPROVED_AT.plusSeconds(60)));

        dispatcher.dispatch(refundRequested(target.id(), "15000"));

        assertThat(refunds()).hasSize(1);
    }

    // 결정 33: 확정 실패 뒤에는 같은 요청이 와도 다시 열지 않는다
    @Test
    void requestAfterRefundFailedIsAcceptedWithoutReopening() {
        paid();
        resolveRefund(new Outcome.Rejected(new ProviderError("EXCEED_MAX_REFUND_DUE", "환불 기한 초과")));

        dispatcher.dispatch(refundRequested(target.id(), "15000"));

        assertThat(refunds()).singleElement()
                .satisfies(refund -> assertThat(refund.status()).isEqualTo(TransactionStatus.FAILED));
    }

    // order 는 결제됐다는데 payment 에 기록이 없다 — 다시 받아도 같으므로 실패로 올려 DLQ 로 보내고 사람이 본다
    @Test
    void requestWithoutPaymentFailsLoudly(CapturedOutput output) {
        assertThatThrownBy(() -> dispatcher.dispatch(refundRequested(target.id(), "15000")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(refunds()).isEmpty();
        assertThat(output.getAll()).contains("환불 요청을 처리하지 못했다 — 사람 확인 필요");
    }

    // 금액은 결제 기록의 것으로 환불한다. 요청 금액이 다르면 알린다
    @Test
    void mismatchedRequestAmountIsReportedAndPaymentAmountIsRefunded(CapturedOutput output) {
        paid();

        dispatcher.dispatch(refundRequested(target.id(), "99999"));

        assertThat(refunds()).singleElement().satisfies(refund -> assertThat(refund.amount()).isEqualTo(AMOUNT));
        assertThat(output.getAll()).contains("환불 요청 금액이 결제 금액과 다르다");
    }

    @Test
    void eventOfAnotherAggregateIsRefused() {
        paid();
        String body = refundRequested(target.id(), "15000").replace("\"aggregateType\":\"ORDER\"",
                "\"aggregateType\":\"PREORDER\"");

        assertThatThrownBy(() -> dispatcher.dispatch(body)).isInstanceOf(IllegalArgumentException.class);
        assertThat(refunds()).isEmpty();
    }

    @Test
    void unknownEventTypeIsRefused() {
        String body = refundRequested(target.id(), "15000").replace("ORDER_REFUND_REQUESTED", "ORDER_SOMETHING");

        assertThatThrownBy(() -> dispatcher.dispatch(body)).isInstanceOf(IllegalArgumentException.class);
    }

    private void paid() {
        PaymentTransaction pending = transactionTemplate.execute(s -> ledger.openCapture(target, AMOUNT));
        ClaimedTransaction started = transactionTemplate.execute(s -> ledger.start(pending, target, paymentKey, AMOUNT))
                .orElseThrow();
        transactionTemplate.executeWithoutResult(s -> ledger.resolve(started, new Outcome.Confirmed(APPROVED_AT)));
    }

    private void resolveRefund(Outcome outcome) {
        PaymentTransaction refund = transactionTemplate.execute(s -> ledger.openRefund(target));
        ClaimedTransaction claimed = transactionTemplate.execute(s -> ledger.claim(refund)).orElseThrow();
        transactionTemplate.executeWithoutResult(s -> ledger.resolve(claimed, outcome));
    }

    private List<PaymentTransaction> refunds() {
        return transactions.findTransactionsByTarget(target).stream()
                .filter(transaction -> transaction.type() == TransactionType.REFUND)
                .toList();
    }

    static String refundRequested(UUID orderId, String amount) {
        return """
                {"eventId":"%s","eventType":"ORDER_REFUND_REQUESTED","aggregateType":"ORDER","aggregateId":"%s",
                 "occurredAt":"2026-10-04T01:00:00Z","payload":{"amount":%s}}
                """.formatted(UUID.randomUUID(), orderId, amount);
    }
}
