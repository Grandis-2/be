package com.grandis.nova.order.draw;

import com.grandis.nova.order.client.payment.DeclineReason;
import com.grandis.nova.order.order.pay.PaymentSettlement;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * payment 가 알린 응모비 결제의 확정 결과(DRAW_ENTRY_PAYMENT_SETTLED). 동기 응답을 잃었을 때 응모가 결과를 받는 길이다.
 *
 * @param amount        거래 금액. 응모비와 대조만 한다(다르면 ERROR — 반영은 한다, 돈의 사실은 결제 쪽이다)
 * @param declineReason DECLINED 일 때만
 */
public record EntryPaymentSettlement(UUID entryId, String providerOrderId, PaymentSettlement.Result result, BigDecimal amount,
                                     DeclineReason declineReason) {

    public EntryPaymentSettlement {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(amount, "amount");
        if (providerOrderId == null || providerOrderId.isBlank()) {
            throw new IllegalArgumentException("결제창 번호가 없다: entryId=" + entryId);
        }
        if ((result == PaymentSettlement.Result.DECLINED) != (declineReason != null)) {
            throw new IllegalArgumentException("거절일 때만 사유가 있다: entryId=" + entryId + ", result=" + result);
        }
    }
}
