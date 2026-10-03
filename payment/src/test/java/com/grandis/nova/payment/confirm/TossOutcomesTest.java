package com.grandis.nova.payment.confirm;

import com.grandis.nova.payment.client.toss.TossCommandResult;
import com.grandis.nova.payment.client.toss.TossPayment;
import com.grandis.nova.payment.client.toss.TossPaymentStatus;
import com.grandis.nova.payment.client.toss.UnknownReason;
import com.grandis.nova.payment.domain.enums.DeclineReason;
import com.grandis.nova.payment.domain.model.Outcome;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 토스 승인 결과 → 도메인 사건 매핑 전체. 결과 불명을 실패로 단정하지 않는 것이 핵심이다. */
class TossOutcomesTest {

    static final Instant APPROVED_AT = Instant.parse("2026-10-02T01:02:03Z");
    static final PaymentTransaction HELD = PaymentTransaction.openCapture(PaymentTarget.order(7L), Money.won(15000),
            Instant.parse("2026-10-02T01:00:00Z"));

    @Test
    void doneWithSameAmountIsConfirmed() {
        assertThat(TossOutcomes.of(succeeded(15000, APPROVED_AT), HELD)).isEqualTo(new Outcome.Confirmed(APPROVED_AT));
    }

    // D15: 토스가 다른 금액으로 승인했다면 돈이 다르게 움직였다 — 승인으로 반영하지 않고 조회 · 사람 판단으로 넘긴다
    @Test
    void doneWithDifferentAmountIsUnknown() {
        assertThat(TossOutcomes.of(succeeded(14000, APPROVED_AT), HELD)).isEqualTo(new Outcome.Unknown(
                new ProviderError(TossOutcomes.AMOUNT_MISMATCH, UnknownReason.MISMATCHED_RESPONSE.name())));
    }

    @Test
    void doneWithoutApprovedAtIsUnknown() {
        assertThat(TossOutcomes.of(succeeded(15000, null), HELD)).isEqualTo(new Outcome.Unknown(
                new ProviderError(TossOutcomes.MISSING_APPROVED_AT, UnknownReason.UNREADABLE_RESPONSE.name())));
    }

    @Test
    void rejectedKeepsProviderCode() {
        assertThat(TossOutcomes.of(new TossCommandResult.Rejected("REJECT_CARD_PAYMENT", "한도초과"), HELD))
                .isEqualTo(new Outcome.Rejected(new ProviderError("REJECT_CARD_PAYMENT", "한도초과")));
    }

    @Test
    void transientAndProcessingAreRetriedLater() {
        assertThat(TossOutcomes.of(new TossCommandResult.Transient("PROVIDER_ERROR", "일시 오류"), HELD))
                .isEqualTo(new Outcome.InProgress(new ProviderError("PROVIDER_ERROR", "일시 오류"), TossOutcomes.RETRY_AFTER));
        assertThat(TossOutcomes.of(new TossCommandResult.Processing("IDEMPOTENT_REQUEST_PROCESSING"), HELD))
                .isEqualTo(new Outcome.InProgress(new ProviderError("IDEMPOTENT_REQUEST_PROCESSING", null),
                        TossOutcomes.RETRY_AFTER));
    }

    // 복구(NV-102)가 푸는 방법을 고르도록 불명 사유는 늘 문구 칸에 남는다. 토스 코드가 없으면 사유가 코드 칸에도 들어간다
    @ParameterizedTest
    @EnumSource(UnknownReason.class)
    void unknownRecordsReason(UnknownReason reason) {
        assertThat(TossOutcomes.of(new TossCommandResult.Unknown(reason, null), HELD))
                .isEqualTo(new Outcome.Unknown(new ProviderError(reason.name(), reason.name())));
        assertThat(TossOutcomes.of(new TossCommandResult.Unknown(reason, "ALREADY_PROCESSED_PAYMENT"), HELD))
                .isEqualTo(new Outcome.Unknown(new ProviderError("ALREADY_PROCESSED_PAYMENT", reason.name())));
    }

    @Test
    void declineReasonFollowsRejectionKind() {
        assertThat(TossOutcomes.declineReasonOf("REJECT_CARD_PAYMENT")).isEqualTo(DeclineReason.CARD_REJECTED);
        assertThat(TossOutcomes.declineReasonOf("NOT_FOUND_PAYMENT_SESSION")).isEqualTo(DeclineReason.PAYMENT_EXPIRED);
        assertThat(TossOutcomes.declineReasonOf("UNAUTHORIZED_KEY")).isEqualTo(DeclineReason.FAILED);
    }

    private static TossCommandResult succeeded(long totalAmount, Instant approvedAt) {
        return new TossCommandResult.Succeeded(new TossPayment("tgen_outcome", HELD.providerOrderId().value(),
                TossPaymentStatus.DONE, totalAmount, totalAmount, "카드", approvedAt, "txkey"));
    }
}
