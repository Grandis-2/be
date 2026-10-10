package com.grandis.nova.payment.confirm;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.payment.ClaimedTransaction;
import com.grandis.nova.payment.PaymentErrorCode;
import com.grandis.nova.payment.PaymentLedger;
import com.grandis.nova.payment.client.toss.TossCommandResult;
import com.grandis.nova.payment.client.toss.TossConfirmRequest;
import com.grandis.nova.payment.client.toss.TossIdempotencyKey;
import com.grandis.nova.payment.client.toss.TossPaymentClient;
import com.grandis.nova.payment.domain.enums.PaymentStatus;
import com.grandis.nova.payment.domain.exception.ActiveTransactionExistsException;
import com.grandis.nova.payment.domain.exception.LeaseLostException;
import com.grandis.nova.payment.domain.exception.PaymentAmountMismatchException;
import com.grandis.nova.payment.domain.exception.PaymentTargetMismatchException;
import com.grandis.nova.payment.domain.model.Outcome;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.domain.repository.PaymentReader;
import com.grandis.nova.payment.domain.repository.PaymentTransactionReader;
import com.grandis.nova.payment.vo.Money;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderOrderId;
import com.grandis.nova.payment.vo.ProviderPaymentKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

/**
 * 결제 승인: 거래를 시작하고(리스) 토스 승인을 부른 뒤 결과 · 결제 기록 · 결과 이벤트를 한 트랜잭션으로 남긴다({@link CaptureSettlement}).
 *
 * 순서: [tx] 시작(대상 · 금액 대조, 대상당 하나 — D13 · D15) → 토스 승인(트랜잭션 밖, 거래의 멱등 키) → [tx] 반영 + 결과 이벤트.
 * 거래 하나 = 트랜잭션 하나(원장 계약)이고 원장 예외는 트랜잭션 경계 밖에서 잡는다.
 *
 * 응답 약속(호출자가 대상을 결제 전으로 되돌려도 되는지가 여기 달렸다):
 * - 시작 전 업무 오류(PaymentErrorCode)는 4xx 다. 그중 결제창 번호 없음 · 금액 불일치 · 미지원 대상은 "이 결제창은 앞으로도 시작될 수
 *   없다" 는 확언이다 — 결제창의 대상 · 금액은 바뀌지 않는다.
 * - 시작한 뒤에는 업무 오류를 내지 않는다. 결과는 늘 200(불명 · 처리 중은 PENDING)이고, 예상 밖 실패는 5xx 다.
 *   확정하지 못한 거래는 리스가 끝나면 복구({@link CaptureRecovery})가 이어 받는다.
 *
 * 같은 결제창 번호로 다시 부르면 토스를 부르지 않고 그 거래의 지금 결과를 돌려준다. startAllowed=false 면 아직 시작하지 않은 거래도
 * 시작하지 않는다 — 호출자가 결제할 수 없다고 판정한 대상의 결과만 회수하는 길이다.
 * 결제 키는 로그 · 예외 · 이벤트에 싣지 않는다. 결제창 번호 · 거래 id 로 추적한다.
 */
@Service
public class ConfirmPaymentService {

    private static final Logger log = LoggerFactory.getLogger(ConfirmPaymentService.class);

    private final PaymentTransactionReader reader;
    private final PaymentReader paymentReader;
    private final PaymentLedger ledger;
    private final TossPaymentClient toss;
    private final CaptureSettlement settlement;
    private final TransactionTemplate writeTransaction;

    public ConfirmPaymentService(PaymentTransactionReader reader, PaymentReader paymentReader, PaymentLedger ledger,
                                 TossPaymentClient toss, CaptureSettlement settlement,
                                 PlatformTransactionManager transactionManager) {
        this.reader = reader;
        this.paymentReader = paymentReader;
        this.ledger = ledger;
        this.toss = toss;
        this.settlement = settlement;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    /**
     * @param target     호출자가 말한 대상. 거래가 그 대상의 것인지 대조한다
     * @param paymentKey 결제창이 돌려준 결제 키
     * @param amount       호출자의 저장 금액(주문 총액). 사용자 입력이 아니다
     * @param startAllowed false 면 시작하지 않고 지금 결과만 돌려준다(아직 시작 전이면 PENDING)
     * @throws BusinessException 시작하지 않았다 — PAYMENT_ATTEMPT_NOT_FOUND · PAYMENT_AMOUNT_MISMATCH · PAYMENT_START_CONFLICT
     */
    public ConfirmResult confirm(ProviderOrderId providerOrderId, PaymentTarget target, ProviderPaymentKey paymentKey,
                                 Money amount, boolean startAllowed) {
        return confirm(providerOrderId, target, paymentKey, amount, startAllowed, false);
    }

    /**
     * @param reserve startAllowed=false 일 때만: 아직 시작 전인 결제창을 확보한다(만료 기준을 지금부터). 호출자가 대상을 승인 중으로
     *                바꾸기 직전의 확인이다 — 확인과 그 전환 사이에 만료가 끼어 만료 결과를 대상이 먼저 받아 버리는 경합을 막는다.
     *                확보와 만료는 같은 조건부 UPDATE 라 만료가 이겼으면 지금 결과가 그것(DECLINED · PAYMENT_EXPIRED)이다
     */
    public ConfirmResult confirm(ProviderOrderId providerOrderId, PaymentTarget target, ProviderPaymentKey paymentKey,
                                 Money amount, boolean startAllowed, boolean reserve) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("결제 승인은 트랜잭션 밖에서 불러야 한다 — 결제사 호출 동안 잠금을 쥐지 않게");
        }
        // 토스 요청 규칙 위반은 시작 전에 드러나야 한다. 시작한 뒤 나면 거래가 리스를 쥔 채 남고 복구도 같은 위반을 되풀이한다
        TossConfirmRequest request = new TossConfirmRequest(paymentKey.value(), providerOrderId.value(),
                amount.amount().longValueExact());
        PaymentTransaction seen = reader.findByProviderOrderId(providerOrderId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND));
        if (!startAllowed) {
            if (!seen.target().equals(target)) {
                throw new BusinessException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND);
            }
            if (reserve) {
                writeTransaction.execute(status -> ledger.reserve(seen));
            }
            return current(seen.id());
        }

        Optional<ClaimedTransaction> claimed;
        try {
            claimed = start(seen, target, paymentKey, amount);
        } catch (PaymentTargetMismatchException e) {
            log.warn("결제 승인 거절 — 다른 대상의 결제창 번호 transactionId={}", seen.id());
            throw new BusinessException(PaymentErrorCode.PAYMENT_ATTEMPT_NOT_FOUND);
        } catch (PaymentAmountMismatchException e) {
            log.warn("결제 승인 거절 — 결제창 금액이 저장 금액과 다름 transactionId={}", seen.id());
            throw new BusinessException(PaymentErrorCode.PAYMENT_AMOUNT_MISMATCH);
        } catch (ActiveTransactionExistsException e) {
            log.info("결제 승인 보류 — 대상에 진행 중 · 성공한 거래가 있음 transactionId={} target={}", seen.id(), target);
            return otherTransactionOf(target);
        }
        if (claimed.isEmpty()) {
            return current(seen.id());
        }
        try {
            return confirmClaimed(claimed.get(), request);
        } catch (BusinessException e) {
            // 시작한 뒤의 4xx 는 호출자가 "시작하지 않았다" 로 읽어 대상을 되돌린다 — 돈이 나간 채 미결제가 된다
            throw new IllegalStateException("시작한 거래에서 업무 오류가 났다 — 4xx 로 내보내지 않는다: "
                    + claimed.get().transaction(), e);
        }
    }

    /** 리스를 쥔 거래를 토스에 보내고 결과를 반영한다. */
    private ConfirmResult confirmClaimed(ClaimedTransaction claimed, TossConfirmRequest request) {
        PaymentTransaction held = claimed.transaction();
        TossCommandResult result = toss.confirm(request, new TossIdempotencyKey(held.idempotencyKey().value()));
        Outcome outcome = TossOutcomes.of(result, held);
        try {
            settlement.settle(claimed, outcome);
        } catch (LeaseLostException e) {
            // 리스가 끝나 복구가 집어 갔다. 결과는 리스를 쥔 쪽이 확정하고 이벤트로 알린다
            log.warn("결제 승인 결과 반영 실패 — 리스를 잃음 transactionId={} outcome={}", held.id(),
                    outcome.getClass().getSimpleName());
            return ConfirmResult.pending();
        }
        return ConfirmResult.of(outcome);
    }

    /**
     * 같은 대상의 동시 시작 셋 이상이 겹치고 앞선 쪽이 롤백되면 교착(1213)이 날 수 있다(D14). 돈에는 영향이 없다 —
     * 한 번 다시 하고, 또 나면 시작하지 않은 채 다시 부르라고 답한다.
     */
    private Optional<ClaimedTransaction> start(PaymentTransaction seen, PaymentTarget target,
                                               ProviderPaymentKey paymentKey, Money amount) {
        try {
            return startOnce(seen, target, paymentKey, amount);
        } catch (PessimisticLockingFailureException first) {
            log.info("결제 시작 교착 — 한 번 다시 transactionId={}", seen.id());
            try {
                return startOnce(seen, target, paymentKey, amount);
            } catch (PessimisticLockingFailureException again) {
                log.warn("결제 시작 교착 반복 — 시작하지 않음 transactionId={}", seen.id(), again);
                throw new BusinessException(PaymentErrorCode.PAYMENT_START_CONFLICT);
            }
        }
    }

    private Optional<ClaimedTransaction> startOnce(PaymentTransaction seen, PaymentTarget target,
                                                   ProviderPaymentKey paymentKey, Money amount) {
        return writeTransaction.execute(status -> ledger.start(seen, target, paymentKey, amount));
    }

    /**
     * 대상에 다른 거래가 진행 중이거나 이미 성공했다(D13). 성공이면 승인으로 답한다 — 그 결과 이벤트를 대상이 이미 소비하고
     * 놓쳤어도 사용자의 재요청으로 회수된다. 진행 중이면 그 거래의 결과 이벤트가 간다.
     */
    private ConfirmResult otherTransactionOf(PaymentTarget target) {
        return paymentReader.findPaymentByTarget(target)
                .filter(payment -> payment.status() == PaymentStatus.SUCCEEDED)
                .map(payment -> ConfirmResult.approved())
                .orElseGet(ConfirmResult::pending);
    }

    /** 그 거래의 지금 결과. 시작 직전에 읽은 행이라 없을 수 없다. */
    private ConfirmResult current(UUID transactionId) {
        PaymentTransaction now = reader.findById(transactionId)
                .orElseThrow(() -> new IllegalStateException("거래가 사라졌다: " + transactionId));
        return ConfirmResult.ofStatus(now.status(), now.lastError() == null ? null : now.lastError().code());
    }
}
