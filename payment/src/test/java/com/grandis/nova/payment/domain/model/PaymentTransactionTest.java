package com.grandis.nova.payment.domain.model;

import com.grandis.nova.payment.domain.enums.PaymentStatus;
import com.grandis.nova.payment.domain.enums.TransactionStatus;
import com.grandis.nova.payment.domain.enums.TransactionType;
import com.grandis.nova.payment.domain.exception.PaymentAmountMismatchException;
import com.grandis.nova.payment.domain.exception.PaymentTargetMismatchException;
import com.grandis.nova.payment.vo.IdempotencyKey;
import com.grandis.nova.payment.vo.LeaseToken;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderError;
import com.grandis.nova.payment.vo.ProviderOrderId;
import com.grandis.nova.payment.vo.ProviderPaymentKey;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static com.grandis.nova.payment.domain.enums.TransactionStatus.FAILED;
import static com.grandis.nova.payment.domain.enums.TransactionStatus.PENDING;
import static com.grandis.nova.payment.domain.enums.TransactionStatus.PROCESSING;
import static com.grandis.nova.payment.domain.enums.TransactionStatus.RETRY_SCHEDULED;
import static com.grandis.nova.payment.domain.enums.TransactionStatus.SUCCEEDED;
import static com.grandis.nova.payment.domain.enums.TransactionType.CAPTURE;
import static com.grandis.nova.payment.domain.enums.TransactionType.REFUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTransactionTest {

    static final Instant NOW = Instant.parse("2026-09-29T01:00:00Z");
    static final Money AMOUNT = Money.won(1_250_000);
    static final ProviderPaymentKey PAYMENT = new ProviderPaymentKey("tgen_payment_1");
    static final PaymentTarget TARGET = PaymentTarget.order(7L);
    static final Payment SUCCEEDED_PAYMENT = Payment.approved(TARGET, PAYMENT, AMOUNT, NOW);
    static final ProviderError ERROR = new ProviderError("REJECT_CARD_PAYMENT", "한도 초과");

    @Test
    void openedCaptureIsPendingWithFreshProviderOrderIdAndIdempotencyKey() {
        PaymentTransaction first = PaymentTransaction.openCapture(TARGET, AMOUNT, NOW);
        PaymentTransaction second = PaymentTransaction.openCapture(TARGET, AMOUNT, NOW);

        assertThat(first.id()).isNull();
        assertThat(first.type()).isEqualTo(CAPTURE);
        assertThat(first.status()).isEqualTo(PENDING);
        assertThat(first.attemptCount()).isZero();
        assertThat(first.providerPaymentKey()).isNull();
        assertThat(first.createdAt()).isEqualTo(NOW);
        assertThat(first.providerOrderId()).isNotEqualTo(second.providerOrderId());
        assertThat(first.idempotencyKey()).isNotEqualTo(second.idempotencyKey());
    }

    @Test
    void refundIsOpenedOnlyFromSucceededPaymentAndTakesItsTargetKeyAndAmount() {
        Payment refunded = new Payment(1L, TARGET, PAYMENT, AMOUNT, PaymentStatus.REFUNDED, NOW, NOW, null, null);

        assertThatThrownBy(() -> PaymentTransaction.openRefund(refunded, NOW)).isInstanceOf(IllegalArgumentException.class);
        PaymentTransaction refund = PaymentTransaction.openRefund(SUCCEEDED_PAYMENT, NOW);
        assertThat(refund.target()).isEqualTo(TARGET);
        assertThat(refund.amount()).isEqualTo(AMOUNT);
    }

    @Test
    void openedRefundIsPendingWithPaymentKeyAndNoProviderOrderId() {
        PaymentTransaction refund = PaymentTransaction.openRefund(SUCCEEDED_PAYMENT, NOW);

        assertThat(refund.type()).isEqualTo(REFUND);
        assertThat(refund.status()).isEqualTo(PENDING);
        assertThat(refund.providerOrderId()).isNull();
        assertThat(refund.providerPaymentKey()).isEqualTo(PAYMENT);
    }

    // ---- 생성자 불변식: DB CHECK 와 같은 것 ----

    @Test
    void captureAndOnlyCaptureHasProviderOrderId() { // ck_payment_tx_provider_order
        assertThatThrownBy(() -> tx(CAPTURE, null, null, PENDING))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tx(REFUND, ProviderOrderId.issue(), PAYMENT, PENDING))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refundAlwaysHasPaymentKey() { // ck_payment_tx_refund_key
        assertThatThrownBy(() -> tx(REFUND, null, null, PENDING)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void startedTransactionHasPaymentKey() { // ck_payment_tx_started_key
        assertThatThrownBy(() -> new PaymentTransaction(1L, TARGET, CAPTURE, ProviderOrderId.issue(), null, AMOUNT,
                IdempotencyKey.issue(), RETRY_SCHEDULED, 1, NOW, null, null, ERROR, NOW, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void attemptCountIsNotNegative() { // ck_payment_tx_numbers
        assertThatThrownBy(() -> new PaymentTransaction(1L, TARGET, CAPTURE, ProviderOrderId.issue(), null, AMOUNT,
                IdempotencyKey.issue(), PENDING, -1, null, null, null, null, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 생성자 불변식: DB 가 허용하지만 상태 머신이 만들 수 없는 것 ----

    @Test
    void processingAndOnlyProcessingHoldsLease() {
        assertThatThrownBy(() -> new PaymentTransaction(1L, TARGET, CAPTURE, ProviderOrderId.issue(), PAYMENT, AMOUNT,
                IdempotencyKey.issue(), PROCESSING, 1, null, null, null, null, NOW, null, NOW))
                .as("리스 없는 PROCESSING 은 누구도 반영하지 못한다").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentTransaction(1L, TARGET, CAPTURE, ProviderOrderId.issue(), PAYMENT, AMOUNT,
                IdempotencyKey.issue(), SUCCEEDED, 1, null, LeaseToken.issue(), NOW, null, NOW, NOW, NOW))
                .as("끝난 행에 리스가 남으면 늦은 반영을 받는다").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentTransaction(1L, TARGET, CAPTURE, ProviderOrderId.issue(), PAYMENT, AMOUNT,
                IdempotencyKey.issue(), PROCESSING, 1, null, LeaseToken.issue(), null, null, NOW, null, NOW))
                .as("리스 토큰과 만료 시각은 짝").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retryScheduledAndOnlyRetryScheduledHasNextRetryAt() {
        assertThatThrownBy(() -> new PaymentTransaction(1L, TARGET, CAPTURE, ProviderOrderId.issue(), PAYMENT, AMOUNT,
                IdempotencyKey.issue(), RETRY_SCHEDULED, 1, null, null, null, ERROR, NOW, null, NOW))
                .as("재시도 시각 없는 RETRY_SCHEDULED 는 워커가 영영 집지 않는다")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentTransaction(1L, TARGET, CAPTURE, ProviderOrderId.issue(), PAYMENT, AMOUNT,
                IdempotencyKey.issue(), PROCESSING, 1, NOW, LeaseToken.issue(), NOW, null, NOW, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void finishedAndOnlyFinishedHasFinishedAt() {
        assertThatThrownBy(() -> new PaymentTransaction(1L, TARGET, CAPTURE, ProviderOrderId.issue(), PAYMENT, AMOUNT,
                IdempotencyKey.issue(), SUCCEEDED, 1, null, null, null, null, NOW, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentTransaction(1L, TARGET, CAPTURE, ProviderOrderId.issue(), PAYMENT, AMOUNT,
                IdempotencyKey.issue(), RETRY_SCHEDULED, 1, NOW, null, null, ERROR, NOW, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedCarriesTheConfirmedError() {
        assertThatThrownBy(() -> new PaymentTransaction(1L, TARGET, CAPTURE, ProviderOrderId.issue(), PAYMENT, AMOUNT,
                IdempotencyKey.issue(), FAILED, 1, null, null, null, null, NOW, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pendingHasNeverBeenSentAndStartedHasBeen() {
        assertThatThrownBy(() -> new PaymentTransaction(1L, TARGET, REFUND, null, PAYMENT, AMOUNT,
                IdempotencyKey.issue(), PENDING, 1, null, null, null, null, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentTransaction(1L, TARGET, REFUND, null, PAYMENT, AMOUNT,
                IdempotencyKey.issue(), PENDING, 0, null, null, null, null, NOW, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentTransaction(1L, TARGET, CAPTURE, ProviderOrderId.issue(), PAYMENT, AMOUNT,
                IdempotencyKey.issue(), PROCESSING, 0, null, LeaseToken.issue(), NOW, null, NOW, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentTransaction(1L, TARGET, CAPTURE, ProviderOrderId.issue(), PAYMENT, AMOUNT,
                IdempotencyKey.issue(), PROCESSING, 1, null, LeaseToken.issue(), NOW, null, null, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 시작 · 선점 · 반영 판정 ----

    @Test
    void captureStartsOnlyWithTheAmountItWasOpenedFor() {
        PaymentTransaction capture = PaymentTransaction.openCapture(TARGET, AMOUNT, NOW);

        assertThat(capture.start(TARGET, AMOUNT)).contains(PROCESSING);
        assertThatThrownBy(() -> capture.start(TARGET, Money.won(1)))
                .isInstanceOf(PaymentAmountMismatchException.class)
                .hasMessageNotContaining("1250000");
    }

    // 다른 대상의 결제창 번호로 온 승인 요청. 금액보다 먼저 본다 — 금액 불일치로 답하면 남의 결제 금액을 떠볼 수 있다.
    @Test
    void captureStartsOnlyForTheTargetItWasOpenedFor() {
        PaymentTransaction capture = PaymentTransaction.openCapture(TARGET, AMOUNT, NOW);

        assertThatThrownBy(() -> capture.start(PaymentTarget.drawEntry(7L), AMOUNT))
                .isInstanceOf(PaymentTargetMismatchException.class);
        assertThatThrownBy(() -> capture.start(PaymentTarget.order(8L), Money.won(1)))
                .isInstanceOf(PaymentTargetMismatchException.class);
    }

    /*
     * 판정 순서는 대상 → 상태 → 금액이다(D17). 이미 시작된 거래는 금액이 달라도 예외 없이 "시작할 수 없음"이다 — 금액 불일치가
     * 상태보다 앞서면 시작된 거래에 409 PAYMENT_AMOUNT_MISMATCH 가 나가고, 호출자는 그것을 "앞으로도 시작될 수 없다" 로 읽어
     * 대상을 되돌린다(돈은 나갔는데 미결제).
     */
    @Test
    void startedCaptureIsNotStartableWhateverTheAmount() {
        assertThat(processing(CAPTURE).start(TARGET, Money.won(1))).isEmpty();
    }

    @Test
    void refundIsNotStartedByUserRequest() {
        assertThat(PaymentTransaction.openRefund(SUCCEEDED_PAYMENT, NOW).start(TARGET, AMOUNT)).isEmpty();
    }

    @Test
    void workerNeverClaimsPendingCapture() {
        assertThat(PaymentTransaction.openCapture(TARGET, AMOUNT, NOW).claim()).isEmpty();
        assertThat(PaymentTransaction.openRefund(SUCCEEDED_PAYMENT, NOW).claim()).contains(PROCESSING);
    }

    @Test
    void resolvingNeedsAHeldLease() {
        PaymentTransaction pending = PaymentTransaction.openCapture(TARGET, AMOUNT, NOW);

        assertThatThrownBy(() -> pending.resolve(new Outcome.Confirmed(NOW)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void processingResolvesByOutcome() {
        PaymentTransaction processing = processing(CAPTURE);

        assertThat(processing.resolve(new Outcome.Confirmed(NOW))).isEqualTo(SUCCEEDED);
        assertThat(processing.resolve(new Outcome.Rejected(ERROR))).isEqualTo(FAILED);
        assertThat(processing.resolve(new Outcome.InProgress(ERROR, Duration.ofSeconds(5)))).isEqualTo(RETRY_SCHEDULED);
        assertThat(processing.resolve(new Outcome.Unknown(ERROR))).isEqualTo(PROCESSING);
    }

    @Test
    void retryDelayIsPositive() {
        assertThatThrownBy(() -> new Outcome.InProgress(ERROR, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringHidesPaymentKey() {
        assertThat(processing(CAPTURE).toString()).doesNotContain("tgen_payment_1");
    }

    static PaymentTransaction processing(TransactionType type) {
        return new PaymentTransaction(1L, TARGET, type, type == CAPTURE ? ProviderOrderId.issue() : null, PAYMENT, AMOUNT,
                IdempotencyKey.issue(), PROCESSING, 1, null, LeaseToken.issue(), NOW.plusSeconds(70), null, NOW, null,
                NOW);
    }

    private static PaymentTransaction tx(TransactionType type, ProviderOrderId providerOrderId,
                                         ProviderPaymentKey paymentKey, TransactionStatus status) {
        return new PaymentTransaction(null, TARGET, type, providerOrderId, paymentKey, AMOUNT, IdempotencyKey.issue(),
                status, 0, null, null, null, null, null, null, NOW);
    }
}
