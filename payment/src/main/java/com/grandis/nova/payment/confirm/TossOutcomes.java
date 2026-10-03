package com.grandis.nova.payment.confirm;

import com.grandis.nova.payment.client.toss.TossCommandResult;
import com.grandis.nova.payment.client.toss.TossPayment;
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
 * 토스 승인 결과 → 결제 도메인 사건. 결제 도메인이 토스를 모르게 둘을 잇는 곳은 여기 하나다(에픽 계획서 §4).
 *
 * 결과 불명은 코드 칸에 토스 코드(없으면 우리가 붙인 코드), 문구 칸에 늘 불명 사유({@link UnknownReason}) 이름을 적는다 —
 * 복구(NV-102)가 사유로 푸는 방법(같은 키 재전송 · 조회)을 고른다. 성공 응답의 금액 · 승인 시각 이상도 조회로 푼다.
 */
final class TossOutcomes {

    private static final Logger log = LoggerFactory.getLogger(TossOutcomes.class);

    /** 처리 중(409) · 일시 오류 뒤 다시 보낼 때까지. 승인 창(인증 뒤 10분) 안에서 여러 번 돌 수 있게 짧게 둔다. */
    static final Duration RETRY_AFTER = Duration.ofSeconds(10);
    /** 승인 응답의 금액이 거래 금액과 다르다(D15). 돈이 다르게 움직였을 수 있어 사람이 본다. */
    static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    /** 승인 응답에 승인 시각이 없다 — 계약 위반. */
    static final String MISSING_APPROVED_AT = "MISSING_APPROVED_AT";

    private TossOutcomes() {
    }

    static Outcome of(TossCommandResult result, PaymentTransaction held) {
        return switch (result) {
            case TossCommandResult.Succeeded succeeded -> approved(succeeded.payment(), held);
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
        return switch (TossRejectionKind.ofConfirm(code)) {
            case BUYER -> DeclineReason.CARD_REJECTED;
            case PAYMENT_SESSION_EXPIRED -> DeclineReason.PAYMENT_EXPIRED;
            case OTHER -> DeclineReason.FAILED;
        };
    }

    /** 성공 응답도 금액 · 승인 시각이 맞아야 승인이다. 아니면 결과 불명으로 두고 사람이 본다(ERROR). */
    private static Outcome approved(TossPayment payment, PaymentTransaction held) {
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
