package com.grandis.nova.payment.confirm;

import com.grandis.nova.payment.client.toss.TossCommandResult;
import com.grandis.nova.payment.client.toss.TossPayment;
import com.grandis.nova.payment.client.toss.TossPaymentStatus;
import com.grandis.nova.payment.client.toss.TossRejectionKind;
import com.grandis.nova.payment.client.toss.UnknownReason;
import com.grandis.nova.payment.domain.enums.DeclineReason;
import com.grandis.nova.payment.domain.model.Outcome;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.vo.ProviderError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * 토스 승인 · 취소 결과 → 결제 도메인 사건. 결제 도메인이 토스를 모르게 둘을 잇는 곳은 여기 하나다(에픽 계획서 §4).
 * 성공 응답의 대조(금액 · 시각)는 명령마다 달라 승인은 여기({@link #approved}), 환불은 RefundRecovery 가 한다.
 *
 * 결과 불명은 코드 칸에 토스 코드(없으면 우리가 붙인 코드), 문구 칸에 늘 불명 사유({@link UnknownReason}) 이름을 적는다 —
 * 복구(CaptureRecovery · RefundRecovery)가 {@link #resolutionOf} 로 푸는 방법(같은 키 재전송 · 조회)을 고른다.
 * 복구가 조회로 확정한 실패는 코드 칸에 {@code LOOKUP_<토스 상태>} 를 적는다 — 토스 코드가 아니라 조회 결과다.
 */
public final class TossOutcomes {

    private static final Logger log = LoggerFactory.getLogger(TossOutcomes.class);

    /** 처리 중(409) · 일시 오류 뒤 다시 보낼 때까지. 승인 창(인증 뒤 10분) 안에서 여러 번 돌 수 있게 짧게 둔다. */
    static final Duration RETRY_AFTER = Duration.ofSeconds(10);
    /** 승인 응답의 금액이 거래 금액과 다르다(D15). 돈이 다르게 움직였을 수 있어 사람이 본다. */
    static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    /** 승인 응답에 승인 시각이 없다 — 계약 위반. */
    static final String MISSING_APPROVED_AT = "MISSING_APPROVED_AT";
    /** 조회로 알게 된 결과의 코드 접두어. 뒤에 토스 결제 상태(READY · ABORTED …)나 NOT_FOUND · 조회 오류가 붙는다. */
    public static final String LOOKUP = "LOOKUP_";

    private TossOutcomes() {
    }

    static Outcome of(TossCommandResult result, PaymentTransaction held) {
        if (result instanceof TossCommandResult.Succeeded succeeded) {
            return approved(succeeded.payment(), held);
        }
        return notSucceeded(result);
    }

    /**
     * 성공이 아닌 응답(거절 · 일시 오류 · 처리 중 · 불명)의 사건. 승인 · 취소가 같은 규칙이다.
     *
     * @throws IllegalArgumentException 성공 응답이다 — 명령마다 대조가 달라 부르는 쪽이 한다
     */
    public static Outcome notSucceeded(TossCommandResult result) {
        return switch (result) {
            case TossCommandResult.Succeeded succeeded ->
                    throw new IllegalArgumentException("성공 응답은 명령마다 대조한다: " + succeeded.payment().status());
            case TossCommandResult.Rejected rejected -> new Outcome.Rejected(new ProviderError(rejected.code(), rejected.message()));
            case TossCommandResult.Transient transientError ->
                    new Outcome.InProgress(new ProviderError(transientError.code(), transientError.message()), RETRY_AFTER);
            case TossCommandResult.Processing processing ->
                    new Outcome.InProgress(new ProviderError(processing.code(), null), RETRY_AFTER);
            case TossCommandResult.Unknown unknown -> new Outcome.Unknown(new ProviderError(
                    unknown.code() == null ? unknown.reason().name() : unknown.code(), unknown.reason().name()));
        };
    }

    static DeclineReason declineReasonOf(String code) {
        if (code.startsWith(LOOKUP)) {
            // 조회로 확정한 실패: 승인이 실패(ABORTED)했거나, 승인 창이 지나도록 처리되지 않았다(인증 만료)
            return code.equals(LOOKUP + TossPaymentStatus.ABORTED) ? DeclineReason.FAILED : DeclineReason.PAYMENT_EXPIRED;
        }
        return switch (TossRejectionKind.ofConfirm(code)) {
            case BUYER -> DeclineReason.CARD_REJECTED;
            case PAYMENT_SESSION_EXPIRED -> DeclineReason.PAYMENT_EXPIRED;
            case OTHER -> DeclineReason.FAILED;
        };
    }

    /**
     * 마지막 결과에 적힌 불명 사유로 푸는 방법. 사유가 없으면(결과를 남기기 전에 멈췄다 — 응답을 받지 못했을 수 있다) 같은 키 재전송,
     * 불명 사유가 아닌 기록(일시 오류 · 조회 오류 · 우리가 붙인 코드)은 조회다.
     */
    public static UnknownReason.Resolution resolutionOf(ProviderError lastError) {
        if (lastError == null) {
            return UnknownReason.Resolution.RESEND;
        }
        for (UnknownReason reason : UnknownReason.values()) {
            if (reason.name().equals(lastError.message())) {
                return reason.resolution();
            }
        }
        return UnknownReason.Resolution.LOOKUP;
    }

    /** 조회로 확정한 실패. */
    static Outcome.Rejected lookedUpFailure(String what) {
        return new Outcome.Rejected(new ProviderError(LOOKUP + what, "조회 결과로 실패 확정: " + what));
    }

    /** 성공 응답도 금액 · 승인 시각이 맞아야 승인이다. 아니면 결과 불명으로 두고 사람이 본다(ERROR). */
    static Outcome approved(TossPayment payment, PaymentTransaction held) {
        if (payment.totalAmount() != held.amount().amount().longValueExact()) {
            log.error("토스 승인 금액이 거래 금액과 다르다 — 결과 불명으로 둔다 transactionId={} orderId={} totalAmount={} amount={}",
                    held.id(), held.providerOrderId().value(), payment.totalAmount(), held.amount().amount());
            return new Outcome.Unknown(new ProviderError(AMOUNT_MISMATCH, UnknownReason.MISMATCHED_RESPONSE.name()));
        }
        if (payment.approvedAt() == null) {
            log.error("토스 승인 응답에 승인 시각이 없다 — 결과 불명으로 둔다 transactionId={} orderId={}",
                    held.id(), held.providerOrderId().value());
            return new Outcome.Unknown(new ProviderError(MISSING_APPROVED_AT, UnknownReason.UNREADABLE_RESPONSE.name()));
        }
        return new Outcome.Confirmed(payment.approvedAt());
    }
}
