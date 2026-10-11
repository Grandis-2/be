package com.grandis.nova.payment.confirm;

import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.payment.ClaimedTransaction;
import com.grandis.nova.payment.PaymentLedger;
import com.grandis.nova.payment.domain.enums.DeclineReason;
import com.grandis.nova.payment.domain.exception.LeaseLostException;
import com.grandis.nova.payment.domain.model.Outcome;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.outbox.DrawEntryPaymentSettled;
import com.grandis.nova.payment.outbox.OrderPaymentSettled;
import com.grandis.nova.payment.outbox.OutboxMessage;
import com.grandis.nova.payment.vo.ProviderError;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * CAPTURE 를 원장에 반영하고 확정 결과를 대상에 알린다. 승인 API(동기)와 복구 · 만료 작업이 같은 길을 써서, 어느 쪽이 확정해도
 * 결제 기록 · 결과 이벤트가 같은 모양으로 한 트랜잭션에 남는다. 한 번 부를 때 트랜잭션 하나다(원장 계약) — 원장 예외는 이 경계
 * 밖으로 그대로 나간다.
 *
 * 알림은 확정 결과(승인 · 거절 · 만료)만이다. 불명 · 처리 중은 대상이 이미 결제 확인 중이다.
 * 대상마다 결과 이벤트가 다르다 — 주문은 ORDER_PAYMENT_SETTLED, 드로우 응모는 DRAW_ENTRY_PAYMENT_SETTLED(모양은 같다).
 */
@Component
public class CaptureSettlement {

    private final PaymentLedger ledger;
    private final OutboxWriter outbox;
    private final TransactionTemplate writeTransaction;

    public CaptureSettlement(PaymentLedger ledger, OutboxWriter outbox, PlatformTransactionManager transactionManager) {
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

    /** @return 만료한 거래. 그사이 시작됐거나 이미 끝났으면 empty */
    public Optional<PaymentTransaction> expire(PaymentTransaction seen, Duration openedFor) {
        return writeTransaction.execute(status -> {
            Optional<PaymentTransaction> expired = ledger.expire(seen, openedFor);
            expired.flatMap(CaptureSettlement::expiredNotice).ifPresent(outbox::append);
            return expired;
        });
    }

    private static Optional<OutboxMessage> notice(PaymentTransaction held, Outcome outcome) {
        return switch (outcome) {
            case Outcome.Confirmed confirmed -> Optional.of(approved(held, confirmed.at()));
            case Outcome.Rejected rejected -> Optional.of(declined(held, TossOutcomes.declineReasonOf(rejected.error().code())));
            case Outcome.InProgress inProgress -> Optional.empty();
            case Outcome.Unknown unknown -> Optional.empty();
        };
    }

    private static Optional<OutboxMessage> expiredNotice(PaymentTransaction expired) {
        return Optional.of(declined(expired, DeclineReason.PAYMENT_EXPIRED));
    }

    private static OutboxMessage approved(PaymentTransaction transaction, Instant approvedAt) {
        return switch (transaction.target().type()) {
            case ORDER -> OrderPaymentSettled.approved(transaction, approvedAt);
            case DRAW_ENTRY -> DrawEntryPaymentSettled.approved(transaction, approvedAt);
        };
    }

    private static OutboxMessage declined(PaymentTransaction transaction, DeclineReason reason) {
        return switch (transaction.target().type()) {
            case ORDER -> OrderPaymentSettled.declined(transaction, reason);
            case DRAW_ENTRY -> DrawEntryPaymentSettled.declined(transaction, reason);
        };
    }
}
