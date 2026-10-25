package com.grandis.nova.payment.refund;

import com.grandis.nova.payment.domain.enums.PaymentStatus;
import com.grandis.nova.payment.domain.enums.TransactionStatus;
import com.grandis.nova.payment.domain.model.Payment;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.confirm.TossOutcomes;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 한 주문의 환불이 어디서 멈췄나와 관리자가 할 수 있는 조치. 판정은 결제 기록과 마지막 환불 거래만 본다 — 결제사 상태는 조치할 때
 * 조회한다(목록마다 결제사를 부르지 않는다). 조치가 목록에 있어도 조회 결과가 맞지 않으면 거절된다.
 *
 * @param paymentStatus 성공한 결제가 없으면 null
 * @param lastRefund    마지막 환불 거래(만든 순). 없으면 null
 */
public record RefundIssue(UUID orderId, Reason reason, PaymentStatus paymentStatus, PaymentTransaction lastRefund,
                          List<Action> actions) {

    /** 태그 · 화면 문구가 갈리는 값이라 이름을 바꾸지 않는다. */
    public enum Reason {
        /** 결제사가 환불을 거절해 실패로 확정했다. */
        REFUND_REJECTED,
        /** 첫 전송부터 24시간 동안 결과를 확정하지 못해 멈췄다. */
        ESCALATED_TIMED_OUT,
        /** 환불할 결제가 결제사에 없다. */
        ESCALATED_NOT_FOUND,
        /** 결제사 조회 상태가 자동으로 확정하지 않는 값이다(부분 취소 · 입금 대기 등). */
        ESCALATED_UNEXPECTED_STATE,
        /** 결제사의 환불 완료 응답이 전액 취소와 맞지 않다(금액 · 잔액 · 취소 시각). */
        ESCALATED_MISMATCH,
        /** 복구 작업이 다루는 중이다. */
        IN_PROGRESS,
        /** 복구 작업이 다루는 중인데 오래됐다. */
        IN_PROGRESS_LONG,
        /** 성공한 결제는 있는데 환불 거래가 없다 — 환불 요청이 닿지 않았다(DLQ 등). */
        NO_REFUND_TRANSACTION,
        /** 성공한 결제가 없다. */
        NO_PAYMENT,
        /** 결제가 이미 환불로 끝났다. */
        REFUNDED
    }

    public enum Action {
        /** 새 거래로 다시 보낸다(결제사가 아직 한 푼도 취소하지 않았을 때만). */
        RETRY,
        /** 결제사에서 직접 환불한 것을 기록한다(결제사 조회로 전액 취소가 확인될 때만). */
        MANUAL_COMPLETE
    }

    /** 결제 기록 · 마지막 환불로 판정한다. 활성 거래가 staleBefore 보다 앞서 열렸으면 오래된 것이다. */
    static RefundIssue of(UUID orderId, Optional<Payment> payment, Optional<PaymentTransaction> lastRefund,
                          Instant staleBefore) {
        if (payment.isEmpty()) {
            return new RefundIssue(orderId, Reason.NO_PAYMENT, null, lastRefund.orElse(null), List.of());
        }
        PaymentStatus paymentStatus = payment.get().status();
        if (paymentStatus == PaymentStatus.REFUNDED) {
            return new RefundIssue(orderId, Reason.REFUNDED, paymentStatus, lastRefund.orElse(null), List.of());
        }
        if (lastRefund.isEmpty()) {
            return new RefundIssue(orderId, Reason.NO_REFUND_TRANSACTION, paymentStatus, null, List.of());
        }
        PaymentTransaction last = lastRefund.get();
        Reason reason = reasonOf(last, staleBefore);
        List<Action> actions = switch (reason) {
            case REFUND_REJECTED, ESCALATED_TIMED_OUT -> List.of(Action.RETRY, Action.MANUAL_COMPLETE);
            case ESCALATED_NOT_FOUND, ESCALATED_UNEXPECTED_STATE, ESCALATED_MISMATCH -> List.of(Action.MANUAL_COMPLETE);
            default -> List.of();
        };
        return new RefundIssue(orderId, reason, paymentStatus, last, actions);
    }

    private static Reason reasonOf(PaymentTransaction last, Instant staleBefore) {
        if (last.status() == TransactionStatus.FAILED) {
            return Reason.REFUND_REJECTED;
        }
        if (last.status() == TransactionStatus.SUCCEEDED) {
            return Reason.REFUNDED;
        }
        if (last.isEscalated()) {
            String code = last.lastError() == null ? "" : last.lastError().code();
            if (RefundRecovery.UNRESOLVED.equals(code)) {
                return Reason.ESCALATED_TIMED_OUT;
            }
            if ((TossOutcomes.LOOKUP + "NOT_FOUND").equals(code)) {
                return Reason.ESCALATED_NOT_FOUND;
            }
            if (RefundRecovery.REFUND_MISMATCH.equals(code) || RefundRecovery.MISSING_CANCELED_AT.equals(code)) {
                return Reason.ESCALATED_MISMATCH;
            }
            return Reason.ESCALATED_UNEXPECTED_STATE;
        }
        return last.createdAt().isBefore(staleBefore) ? Reason.IN_PROGRESS_LONG : Reason.IN_PROGRESS;
    }
}
