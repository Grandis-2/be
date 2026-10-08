package com.grandis.nova.payment;

import com.grandis.nova.payment.domain.enums.PaymentStatus;
import com.grandis.nova.payment.domain.enums.TransactionStatus;
import com.grandis.nova.payment.domain.enums.TransactionType;
import com.grandis.nova.payment.domain.exception.ActiveTransactionExistsException;
import com.grandis.nova.payment.domain.exception.LeaseLostException;
import com.grandis.nova.payment.domain.exception.PaymentAlreadyRefundedException;
import com.grandis.nova.payment.domain.exception.PaymentAmountMismatchException;
import com.grandis.nova.payment.domain.exception.PaymentTargetMismatchException;
import com.grandis.nova.payment.domain.exception.RefundFailedException;
import com.grandis.nova.payment.domain.model.Outcome;
import com.grandis.nova.payment.domain.model.Payment;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.domain.repository.PaymentReader;
import com.grandis.nova.payment.domain.repository.PaymentTransactionReader;
import com.grandis.nova.payment.domain.repository.PaymentTransactionWriter;
import com.grandis.nova.payment.domain.repository.PaymentWriter;
import com.grandis.nova.payment.vo.IdempotencyKey;
import com.grandis.nova.payment.vo.LeaseToken;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderError;
import com.grandis.nova.payment.vo.ProviderPaymentKey;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 결제 거래 · 결제 기록을 바꾸는 유일한 길. 쓰기 포트는 여기서만 쓴다(PaymentArchitectureTest).
 * 전이는 상태 머신({@link TransactionStatus})이 판정하고, 저장소는 읽은 상태 · 리스를 조건으로 한 UPDATE 로 반영한다.
 *
 * <b>트랜잭션 계약</b>
 * <ul>
 *   <li>스스로 트랜잭션을 열지 않는다(MANDATORY). 결과 반영은 같은 트랜잭션의 다른 변경(payments · 아웃박스 기록)과
 *       함께 커밋되거나 함께 되돌아가야 한다.</li>
 *   <li>여기서 나가는 예외(LeaseLost · ActiveTransactionExists · PaymentAlreadyRefunded · 금액 · 대상 불일치 등)는 모두
 *       호출자의 트랜잭션을 rollback-only 로 만든다. 그래서 쓰기는 거래(대상) 하나당 트랜잭션 하나로 한다 — 여러 건을 한
 *       트랜잭션에 묶으면(예: 회차 취소 일괄 환불) 한 건의 중복 · 리스 잃음이 묶음 전체를 되돌린다. 예외는 트랜잭션
 *       경계 밖에서 잡는다 — 안에서 잡고 계속하면 커밋 때 UnexpectedRollbackException 이 난다.</li>
 *   <li>결과 반영이 0행이면 {@link LeaseLostException} 을 던진다 — 호출자의 트랜잭션은 rollback-only 가 되어 그 트랜잭션의
 *       다른 변경도 되돌아간다. 반환값으로 알리지 않는다(무시할 수 있어서). 재시도(@Retryable 등)하지 않는다 —
 *       리스를 잃은 쪽이 다시 반영할 길은 없고, 결과는 리스를 쥔 쪽이 확정한다.</li>
 *   <li>결제사 호출은 트랜잭션 밖이다. 선점(start · claim)을 커밋한 뒤 부르고, 결과는 새 트랜잭션에서 {@link #resolve} 한다.</li>
 * </ul>
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class PaymentLedger {

    private final PaymentTransactionReader reader;
    private final PaymentTransactionWriter writer;
    private final PaymentWriter payments;
    private final PaymentReader paymentReader;
    private final Clock clock;

    public PaymentLedger(PaymentTransactionReader reader, PaymentTransactionWriter writer, PaymentWriter payments,
                         PaymentReader paymentReader, Clock clock) {
        this.reader = reader;
        this.writer = writer;
        this.payments = payments;
        this.paymentReader = paymentReader;
        this.clock = clock;
    }

    /** 결제창을 열 때: 새 CAPTURE(PENDING, 새 결제사 주문 번호 · 멱등 키). */
    public PaymentTransaction openCapture(PaymentTarget target, Money amount) {
        return writer.insert(PaymentTransaction.openCapture(target, amount, clock.instant()));
    }

    /**
     * 대상의 결제를 환불할 때: 저장된 성공 결제를 읽어 그 대상 · 결제 키 · 금액으로 새 REFUND(PENDING, 새 멱등 키)를 연다.
     * 워커가 선점해 보낸다. 결제를 호출자에게서 받지 않는다 — 받으면 조작된 키 · 금액으로 열 수 있다.
     * 결제 행을 잠그고 판정한다 — REFUND 결과 반영({@link #resolve})도 같은 행을 먼저 잠가, 판정과 INSERT 사이에 실패 확정이 끼지 않는다.
     *
     * @throws IllegalStateException            대상에 결제가 없다(호출 쪽 잘못)
     * @throws PaymentAlreadyRefundedException  이미 환불됐다(재전송이면 성공으로 다룰 수 있다)
     * @throws RefundFailedException            마지막 환불이 확정 실패했다 — 자동으로 다시 열지 않는다(결정 33)
     * @throws ActiveTransactionExistsException 그 대상에 진행 중인 REFUND 가 이미 있다
     */
    public PaymentTransaction openRefund(PaymentTarget target) {
        Payment payment = paymentReader.lockPaymentByTarget(target)
                .orElseThrow(() -> new IllegalStateException("환불할 결제가 없다: " + target));
        if (payment.status() == PaymentStatus.REFUNDED) {
            throw new PaymentAlreadyRefundedException(target);
        }
        if (lastRefundFailed(target)) {
            throw new RefundFailedException(target);
        }
        return writer.insert(PaymentTransaction.openRefund(payment, clock.instant()));
    }

    /**
     * 사용자 승인 요청으로 CAPTURE 를 시작한다: PENDING → PROCESSING, 결제 키를 적고 리스를 잡는다.
     *
     * 거래의 금액은 결제창을 열 때 호출자가 주장한 값이라 믿을 수 없다(내부 API 는 주인 · 금액을 확인하지 않는다).
     * 그래서 여기서 호출자의 저장 금액과 다시 대조한다 — 사용자가 승인 요청에 실어 보낸 금액을 넘기면 결제창 금액을 바꿔치기한
     * 승인이 통과한다.
     *
     * @param expectedTarget 호출자가 말한 대상. 거래가 그 대상의 것인지 대조한다
     * @param expectedAmount 호출자의 저장 금액(주문 총액 · 응모비). 사용자 입력을 넘기지 않는다
     * @return 리스를 쥔 거래. 시작할 수 없는 상태였거나 그사이 누가 먼저 시작했으면 empty
     * @throws PaymentTargetMismatchException   다른 대상의 거래다
     * @throws PaymentAmountMismatchException   결제창을 열 때의 금액이 호출자의 저장 금액과 다르다
     * @throws ActiveTransactionExistsException 그 대상에 진행 중 · 성공한 CAPTURE 가 이미 있다(이미 결제 중 · 결제됨)
     */
    public Optional<ClaimedTransaction> start(PaymentTransaction seen, PaymentTarget expectedTarget,
                                              ProviderPaymentKey paymentKey, Money expectedAmount) {
        if (seen.start(expectedTarget, expectedAmount).isEmpty()) {
            return Optional.empty();
        }
        int updated = writer.start(seen, paymentKey, LeaseToken.issue(), PaymentTransaction.LEASE,
                clock.instant());
        return claimed(seen.id(), updated);
    }

    /**
     * 워커가 재전송하려고 선점한다(REFUND PENDING · 재시도 시각이 된 RETRY_SCHEDULED · 리스가 만료된 PROCESSING).
     *
     * @return 새 리스를 쥔 거래. 선점할 수 없거나 아직 때가 아니거나 다른 작업자가 먼저 집었으면 empty
     */
    public Optional<ClaimedTransaction> claim(PaymentTransaction seen) {
        if (seen.claim().isEmpty()) {
            return Optional.empty();
        }
        int updated = writer.claim(seen.id(), seen.status(), seen.leaseToken(), LeaseToken.issue(),
                PaymentTransaction.LEASE, clock.instant());
        return claimed(seen.id(), updated);
    }

    /**
     * 결제사 결과를 반영한다. 리스가 살아 있고 표식이 같을 때만 된다. CAPTURE 확정은 payments 를 만들고, REFUND 확정은
     * 그 결제를 환불로 표시한다 — 거래 행과 같은 트랜잭션이다. REFUND 는 대상의 결제 행을 먼저 잠근다({@link #openRefund} 와 줄 세우기,
     * 잠금 순서는 결제 행 → 거래 행).
     *
     * @throws LeaseLostException    리스를 잃었다(0행). 아무것도 반영하지 않았고 호출자의 트랜잭션은 rollback-only 다
     * @throws IllegalStateException REFUND 를 확정했는데 그 결제 키의 성공 결제가 없다
     */
    public void resolve(ClaimedTransaction claimed, Outcome outcome) {
        PaymentTransaction held = claimed.transaction();
        TransactionStatus to = held.resolve(outcome);
        if (held.type() == TransactionType.REFUND) {
            paymentReader.lockPaymentByTarget(held.target());
        }
        UUID id = held.id();
        LeaseToken lease = held.leaseToken();
        Instant now = clock.instant();
        int updated = switch (outcome) {
            case Outcome.Confirmed confirmed -> writer.finish(id, lease, to, null, now);
            case Outcome.Rejected rejected -> writer.finish(id, lease, to, rejected.error(), now);
            case Outcome.InProgress inProgress ->
                    writer.reschedule(id, lease, inProgress.error(), inProgress.retryAfter());
            case Outcome.Unknown unknown -> writer.recordError(id, lease, unknown.error());
        };
        if (updated != 1) {
            throw new LeaseLostException(id);
        }
        if (outcome instanceof Outcome.Confirmed confirmed) {
            record(held, confirmed, now);
        }
    }

    /**
     * 한 번도 보내지 않은 CAPTURE 를 만료로 닫는다(결제창 인증 기한이 지났다). 돈은 움직이지 않았다 — 결제사에 보낸 적이 없다.
     *
     * @param openedFor 연 지 이만큼 지났을 때만(DB 시각). 저장소가 같은 조건으로 다시 본다
     * @return 만료한 거래. 만료할 수 없거나 아직 때가 아니거나 그사이 시작됐으면 empty
     */
    public Optional<PaymentTransaction> expire(PaymentTransaction seen, Duration openedFor) {
        if (seen.expire().isEmpty()) {
            return Optional.empty();
        }
        if (writer.expire(seen.id(), openedFor, clock.instant()) != 1) {
            return Optional.empty();
        }
        return reader.findById(seen.id());
    }

    /**
     * 호출자가 대상을 승인 중으로 바꾸기 전에 결제창을 확보한다 — 만료 기준을 지금부터 다시 잰다. 만료와 원자적으로 겨룬다: 만료가 먼저면
     * 0행이고 그 거래는 이미 EXPIRED 다(호출자는 지금 결과로 그것을 본다). 아무것도 결제사에 보내지 않는다.
     *
     * @return 확보했으면 true. 시작 전 CAPTURE 가 아니거나 그사이 시작 · 만료됐으면 false
     */
    public boolean reserve(PaymentTransaction seen) {
        if (seen.expire().isEmpty()) {
            return false;
        }
        return writer.reserve(seen.id()) == 1;
    }

    /**
     * 복구가 스스로 끝낼 수 없다고 멈춘다(반복 불명 상한 · 자동 확정 금지 상태). 상태는 그대로다 — 실패로 굳히지 않고 사람이 본다.
     *
     * @throws LeaseLostException 리스를 잃었다(0행)
     */
    public void escalate(ClaimedTransaction claimed, ProviderError error) {
        PaymentTransaction held = claimed.transaction();
        if (writer.escalate(held.id(), held.leaseToken(), error, clock.instant()) != 1) {
            throw new LeaseLostException(held.id());
        }
    }

    /**
     * 같은 행에서 멱등 키를 바꾼다. 결제사가 같은 키의 재요청에 첫 응답(오류 포함)을 돌려주므로, 조회로 처리 안 됨을 확인한 뒤 다시
     * 보낼 때 쓴다. 리스를 새로 잡고 마지막 오류를 지운다(저장소 rotateIdempotencyKey). 언제 바꿔도 되는지는 유형마다 다르다 —
     * 승인은 진행 중인 요청이 없을 때만(CaptureRecovery), 전액 취소는 결제사가 두 번 하지 않으므로 미처리를 확인하면(RefundRecovery).
     *
     * @return 새 키를 든 거래(같은 리스 표식, 새 만료 시각)
     * @throws LeaseLostException 리스를 잃었다(0행)
     */
    public ClaimedTransaction rotateIdempotencyKey(ClaimedTransaction claimed) {
        PaymentTransaction held = claimed.transaction();
        int updated = writer.rotateIdempotencyKey(held.id(), held.leaseToken(), IdempotencyKey.issue(),
                PaymentTransaction.LEASE);
        return claimed(held.id(), updated).orElseThrow(() -> new LeaseLostException(held.id()));
    }

    /** 대상의 마지막 REFUND(만든 순)가 FAILED 인가. 대상별 거래는 몇 행이라 다 읽는다. */
    private boolean lastRefundFailed(PaymentTarget target) {
        return reader.findTransactionsByTarget(target).stream()
                .filter(transaction -> transaction.type() == TransactionType.REFUND)
                .reduce((earlier, later) -> later)
                .map(last -> last.status() == TransactionStatus.FAILED)
                .orElse(false);
    }

    private Optional<ClaimedTransaction> claimed(UUID transactionId, int updated) {
        return updated == 1 ? reader.findById(transactionId).map(ClaimedTransaction::new) : Optional.empty();
    }

    private void record(PaymentTransaction held, Outcome.Confirmed confirmed, Instant now) {
        if (held.type() == TransactionType.CAPTURE) {
            payments.insert(Payment.approved(held.target(), held.providerPaymentKey(), held.amount(), confirmed.at()));
            return;
        }
        if (payments.markRefunded(held.target(), held.providerPaymentKey(), confirmed.at(), now) != 1) {
            throw new IllegalStateException("환불할 성공 결제가 없다: " + held);
        }
    }
}
