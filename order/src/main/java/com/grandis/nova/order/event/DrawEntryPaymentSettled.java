package com.grandis.nova.order.event;

import com.grandis.nova.order.client.payment.DeclineReason;
import com.grandis.nova.order.draw.EntryPaymentSettlement;
import com.grandis.nova.order.order.pay.PaymentSettlement;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * DRAW_ENTRY_PAYMENT_SETTLED 의 payload(payment outbox.DrawEntryPaymentSettled 가 보낸다). 모양은 ORDER_PAYMENT_SETTLED 와 같고, 응모 id 는
 * payload 에 없고 봉투의 aggregateId 로만 온다. 결제 키는 오지 않는다.
 *
 * 칸이 빠지거나 결과와 맞지 않으면 풀 때 실패시킨다(재수신 한도를 넘으면 DLQ).
 *
 * @param approvedAt    승인일 때만. 응모는 저장하지 않는다(결제 시각은 반영한 시각)
 * @param declineReason 거절일 때만
 */
public record DrawEntryPaymentSettled(String providerOrderId, PaymentSettlement.Result result, BigDecimal amount,
                                      Instant approvedAt, DeclineReason declineReason) {

    public DrawEntryPaymentSettled {
        if (providerOrderId == null || providerOrderId.isBlank() || result == null || amount == null
                || (result == PaymentSettlement.Result.APPROVED) != (approvedAt != null)
                || (result == PaymentSettlement.Result.DECLINED) != (declineReason != null)) {
            throw new IllegalArgumentException("응모비 결제 결과에 빠지거나 맞지 않는 칸이 있다: providerOrderId=%s, result=%s"
                    .formatted(providerOrderId, result));
        }
    }

    EntryPaymentSettlement toSettlement(UUID entryId) {
        return new EntryPaymentSettlement(entryId, providerOrderId, result, amount, declineReason);
    }
}
