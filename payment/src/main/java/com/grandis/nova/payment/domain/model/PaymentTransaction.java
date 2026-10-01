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

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 결제 거래(payment_transactions) 한 행의 한 시점 스냅샷. 불변이다 — 상태는 이 객체를 고쳐서 바꾸지 않고
 * {@link com.grandis.nova.payment.PaymentLedger} 가 저장소에서 "읽은 상태 · 리스를 조건으로 한 UPDATE" 로 바꾼다.
 *
 * 생성자는 DB CHECK 와 같은 규칙에 더해, DB 는 허용하지만 상태 머신이 만들 수 없는 조합을 거부한다
 * (리스 없는 PROCESSING, 재시도 시각 없는 RETRY_SCHEDULED, 사유 없는 FAILED 등). 그런 행은 어느 작업자도 끝내지 못하거나
 * 늦은 반영을 받는다.
 *
 * 리스 만료 · 재시도 시각은 DB 시각(UTC_TIMESTAMP(6))으로 쓰고 비교한다 — 서버마다 시계가 달라도 판정이 한 시계로 난다.
 * 그 밖의 시각(requestedAt · finishedAt · createdAt)은 주입받은 Clock 이다.
 *
 * @param id               저장 전이면 null
 * @param target           무엇에 대한 결제인가. 결제는 대상의 상태를 모른다
 * @param providerOrderId  CAPTURE 에만 있다
 * @param providerPaymentKey 시작 전 CAPTURE 에는 없다
 * @param attemptCount     결제사에 보낸(선점한) 횟수
 * @param nextRetryAt      RETRY_SCHEDULED 에만
 * @param leaseToken       PROCESSING 에만
 * @param leaseExpiresAt   PROCESSING 에만
 * @param lastError        FAILED 는 반드시, 나머지는 마지막 오류가 있으면
 * @param requestedAt      처음 보낸 시각. PENDING 이면 없다
 * @param finishedAt       SUCCEEDED · FAILED 에만
 */
public record PaymentTransaction(
        Long id,
        PaymentTarget target,
        TransactionType type,
        ProviderOrderId providerOrderId,
        ProviderPaymentKey providerPaymentKey,
        Money amount,
        IdempotencyKey idempotencyKey,
        TransactionStatus status,
        int attemptCount,
        Instant nextRetryAt,
        LeaseToken leaseToken,
        Instant leaseExpiresAt,
        ProviderError lastError,
        Instant requestedAt,
        Instant finishedAt,
        Instant createdAt
) {

    /**
     * 선점 한 번의 길이(D12): 연결 3초 + 읽기 60초 + 여유 7초. 짧아도 정확성은 안전하다 — 같은 멱등 키 재전송과
     * 리스 조건부 반영이 막는다. 대가는 중복 호출뿐이다. 길면 서버가 죽었을 때 복구가 늦다.
     */
    public static final Duration LEASE = Duration.ofSeconds(70);

    public PaymentTransaction {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        // ck_payment_tx_provider_order
        require((type == TransactionType.CAPTURE) == (providerOrderId != null), "결제사 주문 번호는 CAPTURE 에만 있다");
        // ck_payment_tx_refund_key
        require(type != TransactionType.REFUND || providerPaymentKey != null, "REFUND 는 결제 키가 있어야 한다");
        // ck_payment_tx_started_key
        require(status == TransactionStatus.PENDING || providerPaymentKey != null, "시작한 거래는 결제 키가 있어야 한다");
        // ck_payment_tx_numbers (금액은 Money 가 막는다)
        require(attemptCount >= 0, "보낸 횟수는 0 이상이다");
        // 여기부터는 DB 가 허용하지만 상태 머신이 만들지 않는 조합이다.
        boolean pending = status == TransactionStatus.PENDING;
        require(pending == (attemptCount == 0), "PENDING 만 한 번도 보내지 않았다");
        require(pending == (requestedAt == null), "PENDING 만 보낸 시각이 없다");
        require((leaseToken == null) == (leaseExpiresAt == null), "리스 표식과 만료 시각은 짝이다");
        require((status == TransactionStatus.PROCESSING) == (leaseToken != null), "PROCESSING 만 리스를 쥔다");
        require((status == TransactionStatus.RETRY_SCHEDULED) == (nextRetryAt != null),
                "RETRY_SCHEDULED 만 재시도 시각이 있다");
        require(status.isFinished() == (finishedAt != null), "끝난 거래만 끝난 시각이 있다");
        require(status != TransactionStatus.FAILED || lastError != null, "FAILED 는 확정 오류를 남긴다");
    }

    /** 결제창을 열 때의 새 CAPTURE. 결제사 주문 번호 · 멱등 키를 새로 발급한다(D11). */
    public static PaymentTransaction openCapture(PaymentTarget target, Money amount, Instant now) {
        return new PaymentTransaction(null, target, TransactionType.CAPTURE, ProviderOrderId.issue(), null, amount,
                IdempotencyKey.issue(), TransactionStatus.PENDING, 0, null, null, null, null, null, null, now);
    }

    /**
     * 성공한 결제를 전액 환불하는 새 REFUND. 대상 · 결제 키 · 금액은 그 결제에서 가져온다 — 호출자가 따로 넘기면
     * 다른 결제의 키나 다른 금액으로 환불 행을 열 수 있다. 재시도는 이 행에서 한다.
     *
     * @throws IllegalArgumentException 이미 환불된 결제다
     */
    public static PaymentTransaction openRefund(Payment payment, Instant now) {
        if (payment.status() != PaymentStatus.SUCCEEDED) {
            throw new IllegalArgumentException("성공한 결제만 환불한다: " + payment);
        }
        return new PaymentTransaction(null, payment.target(), TransactionType.REFUND, null,
                payment.providerPaymentKey(), payment.amount(), IdempotencyKey.issue(), TransactionStatus.PENDING, 0,
                null, null, null, null, null, null, now);
    }

    /**
     * 사용자 승인 요청으로 시작할 수 있으면 다음 상태. 이 스냅샷 기준의 판정이다 — 그사이 바뀌었으면 저장소의 조건부 UPDATE 가 0행이 된다.
     *
     * @param expectedTarget 호출자가 말한 대상. 승인 요청의 결제창 번호가 그 대상의 것인지 여기서 대조한다 —
     *                       호출자(order · draw)마다 기억하게 두면 한 곳만 빠뜨려도 남의 결제창으로 승인된다
     * @throws PaymentTargetMismatchException 다른 대상의 거래다. 상태 · 금액보다 먼저 본다(남의 거래를 떠볼 수 없게)
     * @throws PaymentAmountMismatchException 시작할 수 있는데 금액이 결제창을 열 때와 다르다
     */
    public Optional<TransactionStatus> start(PaymentTarget expectedTarget, Money requestedAmount) {
        if (!target.equals(expectedTarget)) {
            throw new PaymentTargetMismatchException(id);
        }
        Optional<TransactionStatus> next = status.start(type);
        if (next.isPresent() && !amount.equals(requestedAmount)) {
            throw new PaymentAmountMismatchException(id);
        }
        return next;
    }

    /** 워커가 선점할 수 있으면 다음 상태. 시각 조건(리스 만료 · 재시도 시각)은 저장소가 DB 시각으로 본다. */
    public Optional<TransactionStatus> claim() {
        return status.claim(type);
    }

    /**
     * 결과를 반영한 다음 상태. 선점해 리스를 쥔 스냅샷에만 부른다.
     *
     * @throws IllegalArgumentException 리스를 쥔 스냅샷(PROCESSING)이 아니다 — 호출하는 코드의 잘못이다
     */
    public TransactionStatus resolve(Outcome outcome) {
        return status.resolve(outcome).orElseThrow(() -> new IllegalArgumentException(
                "리스를 쥔 거래에만 결과를 반영한다: " + this));
    }

    @Override
    public String toString() {
        return "PaymentTransaction[id=%s, target=%s, type=%s, status=%s, attemptCount=%d]"
                .formatted(id, target, type, status, attemptCount);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
