package com.grandis.nova.payment.refund;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.payment.ClaimedTransaction;
import com.grandis.nova.payment.PaymentErrorCode;
import com.grandis.nova.payment.PaymentLedger;
import com.grandis.nova.payment.client.toss.TossLookupResult;
import com.grandis.nova.payment.client.toss.TossPayment;
import com.grandis.nova.payment.client.toss.TossPaymentClient;
import com.grandis.nova.payment.client.toss.TossPaymentStatus;
import com.grandis.nova.payment.domain.enums.PaymentStatus;
import com.grandis.nova.payment.domain.enums.TransactionStatus;
import com.grandis.nova.payment.domain.enums.TransactionType;
import com.grandis.nova.payment.domain.exception.LeaseLostException;
import com.grandis.nova.payment.domain.exception.PaymentAlreadyRefundedException;
import com.grandis.nova.payment.domain.exception.RefundStateChangedException;
import com.grandis.nova.payment.domain.model.Outcome;
import com.grandis.nova.payment.domain.model.Payment;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.domain.repository.PaymentReader;
import com.grandis.nova.payment.domain.repository.PaymentTransactionReader;
import com.grandis.nova.payment.vo.PaymentTarget;
import com.grandis.nova.payment.vo.ProviderError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 멈춘 환불을 관리자가 푼다(주문 대상만). 자동 경로(환불 요청 · 복구)는 확정 실패 · 에스컬레이션 뒤 스스로 다시 열지 않는다(결정 33) —
 * 이것은 사람이 원인을 확인한 뒤의 길이고, 돈이 두 번 나가지 않게 결제사 조회로 먼저 확인한 다음에만 거래 행을 바꾼다.
 *
 * <ul>
 *   <li>다시 보내기: 확정 실패 · 결과를 끝내 몰라 멈춘(24시간) 환불만. 결제사가 아직 한 푼도 취소하지 않았을 때만(DONE · 잔액 = 처음 금액) —
 *       부분 취소 · 결제사 밖 환불 뒤 다시 보내면 잔액 전부를 또 돌려준다. 새 거래(새 멱등 키)를 연다 — 결제사는 오류 응답도 멱등 키에 저장해
 *       같은 키로는 같은 거절만 온다. 멈춘 거래는 닫고(FAILED, 관리자 사유) 새로 연다. 그 밖의 멈춤(결제가 결제사에 없음 · 상태 · 금액
 *       어긋남)은 다시 보내도 같아 받지 않는다.</li>
 *   <li>직접 환불 기록: 관리자가 결제사 상점 관리자에서 환불한 뒤. 결제사 조회가 전액 취소(복구와 같은 판정)일 때만 그 거래를 환불 완료로
 *       반영한다 — 결과 이벤트(ORDER_REFUND_SETTLED REFUNDED)가 같은 트랜잭션에서 나가 주문이 평소처럼 끝난다.</li>
 * </ul>
 * 결제사 조회는 트랜잭션 밖이고, 반영은 관리자가 본 마지막 거래가 그대로일 때만이다(대상의 결제 행 잠금 · 리스 조건). 조회와 반영 사이에
 * 결제사 상태가 바뀌어도 안전하다 — 취소는 되돌아가지 않고, 새 거래의 재전송은 결제사가 두 번 전액 취소하지 않는다(복구의 방어와 같다).
 * 받아들인 조치는 같은 트랜잭션에서 기록하고({@link RefundAdminActions}), 거절은 아무것도 바꾸지 않고 로그만 남긴다. 결제 키는 싣지 않는다.
 */
@Service
public class RefundAdmin {

    private static final Logger log = LoggerFactory.getLogger(RefundAdmin.class);

    /** 멈춘 거래를 닫고 새로 열 때 그 거래에 남기는 사유. 결제사 거절이 아니다. */
    static final String ADMIN_RETRY = "ADMIN_RETRY";
    public static final int MAX_ISSUES = 500;

    private final PaymentTransactionReader transactions;
    private final PaymentReader payments;
    private final PaymentLedger ledger;
    private final RefundSettlement settlement;
    private final TossPaymentClient toss;
    private final RefundIssueQuery issueQuery;
    private final RefundAdminActions actions;
    private final TransactionTemplate writeTransaction;
    private final Clock clock;

    RefundAdmin(PaymentTransactionReader transactions, PaymentReader payments, PaymentLedger ledger,
                RefundSettlement settlement, TossPaymentClient toss, RefundIssueQuery issueQuery, RefundAdminActions actions,
                PlatformTransactionManager transactionManager, Clock clock) {
        this.transactions = transactions;
        this.payments = payments;
        this.ledger = ledger;
        this.settlement = settlement;
        this.toss = toss;
        this.issueQuery = issueQuery;
        this.actions = actions;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** 결제 쪽에서 멈췄을 수 있는 주문들(만든 순, 상한 {@link #MAX_ISSUES}). 진행 중은 staleFor 보다 오래 열렸을 때만. */
    public List<RefundIssue> issues(Duration staleFor) {
        Instant staleBefore = clock.instant().minus(staleFor);
        return issueQuery.stuckOrderIds(staleBefore, MAX_ISSUES).stream()
                .map(orderId -> issueOf(orderId, staleBefore))
                .toList();
    }

    /** 주어진 주문들의 결제 쪽 판정(멈추지 않았어도). 주문 쪽이 고른 주문에 결제 상태를 붙일 때 쓴다. */
    public List<RefundIssue> issuesOf(List<UUID> orderIds, Duration staleFor) {
        Instant staleBefore = clock.instant().minus(staleFor);
        return orderIds.stream().distinct().map(orderId -> issueOf(orderId, staleBefore)).toList();
    }

    /**
     * 다시 보낸다.
     *
     * @param expectedTransactionId 관리자가 본 마지막 환불
     * @return 새로 연 거래(PENDING). 워커가 집어 보낸다
     */
    public RefundAdminResult retry(UUID orderId, UUID expectedTransactionId, String note, String requestedBy) {
        PaymentTarget target = PaymentTarget.order(orderId);
        Current current = current(target, expectedTransactionId);
        RefundIssue issue = RefundIssue.of(orderId, Optional.of(current.payment()), Optional.of(current.last()),
                clock.instant());
        if (!issue.actions().contains(RefundIssue.Action.RETRY)) {
            throw refused(inProgress(issue) ? PaymentErrorCode.REFUND_IN_PROGRESS : PaymentErrorCode.REFUND_NOT_RETRYABLE,
                    orderId, requestedBy, "RETRY", Map.of("reason", issue.reason().name()));
        }
        TossPayment provider = lookup(current, orderId, requestedBy, "RETRY");
        if (provider.status() != TossPaymentStatus.DONE || provider.balanceAmount() != provider.totalAmount()
                || provider.totalAmount() != current.payment().amount().amount().longValueExact()) {
            throw refused(PaymentErrorCode.REFUND_NOT_RETRYABLE, orderId, requestedBy, "RETRY", providerDetails(provider,
                    provider.status() == TossPaymentStatus.CANCELED ? "PROVIDER_CANCELED" : "PROVIDER_NOT_UNTOUCHED"));
        }
        PaymentTransaction last = current.last();
        boolean escalated = last.isEscalated();
        PaymentTransaction opened = write(orderId, requestedBy, "RETRY", () -> {
            if (escalated) {
                ClaimedTransaction held = ledger.takeOverEscalated(last)
                        .orElseThrow(() -> new RefundStateChangedException(target, last.id()));
                ledger.resolve(held, new Outcome.Rejected(new ProviderError(ADMIN_RETRY,
                        "관리자가 결과를 끝내 모른 환불을 닫고 새 거래로 다시 보냄")));
            }
            PaymentTransaction reopened = ledger.reopenRefund(target, last.id());
            actions.record(target, escalated ? RefundAdminActions.Kind.RETRY_AFTER_ESCALATION : RefundAdminActions.Kind.RETRY,
                    last.id(), reopened.id(), requestedBy, note, provider, clock.instant());
            return reopened;
        });
        log.warn("관리자 환불 다시 보내기 — 새 환불을 열었다 orderId={} previousTransactionId={} transactionId={} requestedBy={}",
                orderId, last.id(), opened.id(), requestedBy);
        return new RefundAdminResult(opened.id(), opened.status(), null);
    }

    /**
     * 결제사에서 직접 환불한 것을 기록한다.
     *
     * @param expectedTransactionId 관리자가 본 마지막 환불
     * @return 환불 완료로 반영한 거래와 결제사의 취소 시각
     */
    public RefundAdminResult manualComplete(UUID orderId, UUID expectedTransactionId, String note, String requestedBy) {
        PaymentTarget target = PaymentTarget.order(orderId);
        Current current = current(target, expectedTransactionId);
        RefundIssue issue = RefundIssue.of(orderId, Optional.of(current.payment()), Optional.of(current.last()),
                clock.instant());
        if (!issue.actions().contains(RefundIssue.Action.MANUAL_COMPLETE)) {
            throw refused(inProgress(issue) ? PaymentErrorCode.REFUND_IN_PROGRESS : PaymentErrorCode.REFUND_NOT_RETRYABLE,
                    orderId, requestedBy, "MANUAL_COMPLETE", Map.of("reason", issue.reason().name()));
        }
        TossPayment provider = lookup(current, orderId, requestedBy, "MANUAL_COMPLETE");
        Optional<Instant> canceledAt = RefundRecovery.canceledAt(provider);
        if (!RefundRecovery.fullyCanceled(provider, current.payment().amount()) || canceledAt.isEmpty()) {
            throw refused(PaymentErrorCode.REFUND_NOT_CANCELED_AT_PROVIDER, orderId, requestedBy, "MANUAL_COMPLETE",
                    providerDetails(provider, null));
        }
        PaymentTransaction last = current.last();
        UUID settled = write(orderId, requestedBy, "MANUAL_COMPLETE", () -> {
            ClaimedTransaction held = last.isEscalated()
                    ? ledger.takeOverEscalated(last).orElseThrow(() -> new RefundStateChangedException(target, last.id()))
                    : ledger.claim(ledger.reopenRefund(target, last.id()))
                            .orElseThrow(() -> new IllegalStateException("방금 연 환불을 선점하지 못했다: " + target));
            settlement.settle(held, new Outcome.Confirmed(canceledAt.get()));
            actions.record(target, RefundAdminActions.Kind.MANUAL_COMPLETE, last.id(), held.transaction().id(),
                    requestedBy, note, provider, clock.instant());
            return held.transaction().id();
        });
        log.warn("관리자 직접 환불 기록 — 환불 완료로 반영했다 orderId={} previousTransactionId={} transactionId={} requestedBy={}",
                orderId, last.id(), settled, requestedBy);
        return new RefundAdminResult(settled, TransactionStatus.SUCCEEDED, canceledAt.get());
    }

    private static boolean inProgress(RefundIssue issue) {
        return issue.reason() == RefundIssue.Reason.IN_PROGRESS || issue.reason() == RefundIssue.Reason.IN_PROGRESS_LONG;
    }

    private RefundIssue issueOf(UUID orderId, Instant staleBefore) {
        PaymentTarget target = PaymentTarget.order(orderId);
        return RefundIssue.of(orderId, payments.findPaymentByTarget(target), lastRefund(target), staleBefore);
    }

    private Optional<PaymentTransaction> lastRefund(PaymentTarget target) {
        return transactions.findTransactionsByTarget(target).stream()
                .filter(transaction -> transaction.type() == TransactionType.REFUND)
                .reduce((earlier, later) -> later);
    }

    /** 관리자가 본 마지막 환불이 지금의 마지막이고 결제가 아직 환불 전인가. 판정용 스냅샷이다 — 반영은 원장 조건이 다시 본다. */
    private Current current(PaymentTarget target, UUID expectedTransactionId) {
        Payment payment = payments.findPaymentByTarget(target)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.REFUND_NOT_FOUND));
        if (payment.status() == PaymentStatus.REFUNDED) {
            throw new BusinessException(PaymentErrorCode.REFUND_ALREADY_COMPLETED);
        }
        PaymentTransaction last = lastRefund(target)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.REFUND_NOT_FOUND));
        if (!last.id().equals(expectedTransactionId)) {
            throw stateChanged(last.id());
        }
        return new Current(payment, last);
    }

    private TossPayment lookup(Current current, UUID orderId, String requestedBy, String action) {
        return switch (toss.findByPaymentKey(current.payment().providerPaymentKey().value())) {
            case TossLookupResult.Found found -> found.payment();
            case TossLookupResult.NotFound notFound -> throw refused(
                    action.equals("RETRY") ? PaymentErrorCode.REFUND_NOT_RETRYABLE : PaymentErrorCode.REFUND_NOT_CANCELED_AT_PROVIDER,
                    orderId, requestedBy, action, Map.of("reason", "PROVIDER_NOT_FOUND"));
            case TossLookupResult.Unknown unknown -> {
                log.warn("관리자 환불 조치 — 결제사 조회 불명, 아무것도 바꾸지 않음 orderId={} action={} reason={} requestedBy={}",
                        orderId, action, unknown.reason(), requestedBy);
                throw new BusinessException(PaymentErrorCode.REFUND_PROVIDER_UNAVAILABLE);
            }
        };
    }

    /** 반영 트랜잭션 하나. 원장 예외는 경계 밖에서 업무 오류로 바꾼다 — 그사이 끼어든 조치 · 결과 반영은 "상태가 바뀌었다" 다. */
    private <T> T write(UUID orderId, String requestedBy, String action, Supplier<T> work) {
        try {
            return writeTransaction.execute(status -> work.get());
        } catch (RefundStateChangedException | LeaseLostException e) {
            log.info("관리자 환불 조치 — 그사이 상태가 바뀌어 반영하지 않음 orderId={} action={} requestedBy={}", orderId, action,
                    requestedBy);
            throw stateChanged(lastRefund(PaymentTarget.order(orderId)).map(PaymentTransaction::id).orElse(null));
        } catch (PaymentAlreadyRefundedException e) {
            throw new BusinessException(PaymentErrorCode.REFUND_ALREADY_COMPLETED);
        }
    }

    private static BusinessException stateChanged(UUID currentTransactionId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("currentTransactionId", currentTransactionId == null ? null : currentTransactionId.toString());
        return new BusinessException(PaymentErrorCode.REFUND_STATE_CHANGED, details);
    }

    private static BusinessException refused(PaymentErrorCode code, UUID orderId, String requestedBy, String action,
                                             Map<String, Object> details) {
        log.info("관리자 환불 조치 거절 orderId={} action={} code={} details={} requestedBy={}", orderId, action, code, details,
                requestedBy);
        return new BusinessException(code, details);
    }

    private static Map<String, Object> providerDetails(TossPayment provider, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (reason != null) {
            details.put("reason", reason);
        }
        details.put("providerStatus", provider.status().name());
        details.put("totalAmount", provider.totalAmount());
        details.put("balanceAmount", provider.balanceAmount());
        details.put("cancelCount", provider.cancels().size());
        return details;
    }

    private record Current(Payment payment, PaymentTransaction last) {
    }

    /**
     * @param refundedAt 직접 환불 기록이면 결제사의 취소 시각. 다시 보내기면 null
     */
    public record RefundAdminResult(UUID transactionId, TransactionStatus status, Instant refundedAt) {
    }
}
