package com.grandis.nova.payment.refund;

import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.payment.ClaimedTransaction;
import com.grandis.nova.payment.PaymentLedger;
import com.grandis.nova.payment.domain.exception.LeaseLostException;
import com.grandis.nova.payment.domain.model.Outcome;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.outbox.OrderRefundSettled;
import com.grandis.nova.payment.outbox.OutboxMessage;
import com.grandis.nova.payment.vo.ProviderError;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * REFUND 를 원장에 반영하고 확정 결과를 대상에 알린다. 결과 반영 · payments 환불 표시 · 결과 이벤트가 한 트랜잭션이다.
 * 한 번 부를 때 트랜잭션 하나다(원장 계약) — 원장 예외는 이 경계 밖으로 그대로 나간다.
 *
 * 알림은 확정 결과(완료 · 실패)만이다. 불명 · 처리 중 · 에스컬레이션은 대상이 이미 취소 중이라 알리지 않는다.
 * 드로우 응모(DRAW_ENTRY)는 환불하지 않는다(낙첨 응모비도 돌려주지 않는 규칙) — 환불 요청 경로가 없으므로 알리지 않는다.
 */
@Component
public class RefundSettlement {

    private final PaymentLedger ledger;
    private final OutboxWriter outbox;
    private final TransactionTemplate writeTransaction;

    public RefundSettlement(PaymentLedger ledger, OutboxWriter outbox, PlatformTransactionManager transactionManager) {
        this.ledger = ledger;
        this.outbox = outbox;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    /** @throws LeaseLostException 리스를 잃었다. 아무것도 남기지 않았다 — 리스를 쥔 쪽이 확정한다 */
    public void settle(ClaimedTransaction claimed, Outcome outcome) {
        PaymentTransaction held = claimed.transaction();
        writeTransaction.executeWithoutResult(status -> {
            ledger.resolve(claimed, outcome);
            notice(held, outcome).ifPresent(outbox::append);
        });
    }

    /** @throws LeaseLostException 리스를 잃었다 */
    public void escalate(ClaimedTransaction claimed, ProviderError error) {
        writeTransaction.executeWithoutResult(status -> ledger.escalate(claimed, error));
    }

    /** @throws LeaseLostException 리스를 잃었다 */
    public ClaimedTransaction rotateIdempotencyKey(ClaimedTransaction claimed) {
        return writeTransaction.execute(status -> ledger.rotateIdempotencyKey(claimed));
    }

    private static Optional<OutboxMessage> notice(PaymentTransaction held, Outcome outcome) {
        if (!notifiable(held)) {
            return Optional.empty();
        }
        return switch (outcome) {
            case Outcome.Confirmed confirmed -> Optional.of(OrderRefundSettled.refunded(held, confirmed.at()));
            case Outcome.Rejected rejected -> Optional.of(OrderRefundSettled.failed(held));
            case Outcome.InProgress inProgress -> Optional.empty();
            case Outcome.Unknown unknown -> Optional.empty();
        };
    }

    private static boolean notifiable(PaymentTransaction transaction) {
        return switch (transaction.target().type()) {
            case ORDER -> true;
            case DRAW_ENTRY -> false;
        };
    }
}
