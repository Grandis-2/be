package com.grandis.nova.payment.outbox;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.grandis.nova.payment.domain.enums.DeclineReason;
import com.grandis.nova.payment.domain.enums.TargetType;
import com.grandis.nova.payment.domain.model.PaymentTransaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 드로우 응모비 결제의 확정 결과(승인 · 거절). order 의 결과 소비기가 받아 응모를 전이한다 — 동기 응답을 잃어도 응모가 결과를 받는다.
 * 모양 · 규칙은 {@link OrderPaymentSettled} 와 같다(결과 불명 · 처리 중은 보내지 않는다, 결제 키는 싣지 않는다).
 *
 * payload: {providerOrderId, result, amount, approvedAt, declineReason}.
 *
 * @param entryId       응모 id(결제 대상 id). 봉투의 aggregateId 로만 나가고 payload 에는 싣지 않는다
 * @param amount        거래 금액(원). 받는 쪽은 응모비와 대조만 한다
 * @param approvedAt    승인일 때만. 결제사가 알린 승인 시각
 * @param declineReason 거절일 때만
 */
public record DrawEntryPaymentSettled(@JsonIgnore UUID entryId, String providerOrderId, OrderPaymentSettled.Result result,
                                      BigDecimal amount, Instant approvedAt, DeclineReason declineReason) implements OutboxMessage {

    public DrawEntryPaymentSettled {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(amount, "amount");
        if (providerOrderId == null || providerOrderId.isBlank()) {
            throw new IllegalArgumentException("providerOrderId 가 없다: entryId=" + entryId);
        }
        if ((result == OrderPaymentSettled.Result.APPROVED) != (approvedAt != null)
                || (result == OrderPaymentSettled.Result.DECLINED) != (declineReason != null)) {
            throw new IllegalArgumentException("승인은 승인 시각만, 거절은 사유만 있다: result=" + result);
        }
    }

    public static DrawEntryPaymentSettled approved(PaymentTransaction transaction, Instant approvedAt) {
        return new DrawEntryPaymentSettled(entryIdOf(transaction), transaction.providerOrderId().value(), OrderPaymentSettled.Result.APPROVED,
                transaction.amount().amount(), Objects.requireNonNull(approvedAt, "approvedAt"), null);
    }

    public static DrawEntryPaymentSettled declined(PaymentTransaction transaction, DeclineReason reason) {
        return new DrawEntryPaymentSettled(entryIdOf(transaction), transaction.providerOrderId().value(), OrderPaymentSettled.Result.DECLINED,
                transaction.amount().amount(), null, Objects.requireNonNull(reason, "reason"));
    }

    @Override
    public OutboundEventType eventType() {
        return OutboundEventType.DRAW_ENTRY_PAYMENT_SETTLED;
    }

    @Override
    public AggregateType aggregateType() {
        return AggregateType.DRAW_ENTRY;
    }

    @Override
    public UUID aggregateId() {
        return entryId;
    }

    private static UUID entryIdOf(PaymentTransaction transaction) {
        if (transaction.target().type() != TargetType.DRAW_ENTRY) {
            throw new IllegalArgumentException("응모비 결제가 아니다: " + transaction);
        }
        return transaction.target().id();
    }
}
