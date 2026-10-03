package com.grandis.nova.payment.confirm;

import com.grandis.nova.payment.domain.enums.DeclineReason;
import com.grandis.nova.payment.domain.enums.TransactionStatus;
import com.grandis.nova.payment.domain.model.Outcome;

import java.util.Objects;

/**
 * 승인 요청에 돌려줄 결과. 결제사 응답이 아니라 거래에 반영한 결과다.
 *
 * @param declineReason DECLINED 일 때만
 */
public record ConfirmResult(Result result, DeclineReason declineReason) {

    public ConfirmResult {
        Objects.requireNonNull(result, "result");
        if ((result == Result.DECLINED) != (declineReason != null)) {
            throw new IllegalArgumentException("거절일 때만 사유가 있다: result=" + result);
        }
    }

    public static ConfirmResult approved() {
        return new ConfirmResult(Result.APPROVED, null);
    }

    public static ConfirmResult declined(DeclineReason reason) {
        return new ConfirmResult(Result.DECLINED, reason);
    }

    public static ConfirmResult pending() {
        return new ConfirmResult(Result.PENDING, null);
    }

    /** 이번에 반영한 결과. 처리 중 · 불명은 PENDING 이다. */
    static ConfirmResult of(Outcome outcome) {
        return switch (outcome) {
            case Outcome.Confirmed confirmed -> approved();
            case Outcome.Rejected rejected -> declined(TossOutcomes.declineReasonOf(rejected.error().code()));
            case Outcome.InProgress inProgress -> pending();
            case Outcome.Unknown unknown -> pending();
        };
    }

    /** 이미 시작됐거나 끝난 거래의 지금 결과. 실패 확정은 마지막 오류 코드로, 만료는 결제창 만료로 사유를 싣는다. */
    static ConfirmResult ofStatus(TransactionStatus status, String lastErrorCode) {
        return switch (status) {
            case SUCCEEDED -> approved();
            case FAILED -> declined(TossOutcomes.declineReasonOf(lastErrorCode));
            case EXPIRED -> declined(DeclineReason.PAYMENT_EXPIRED);
            case PENDING, PROCESSING, RETRY_SCHEDULED -> pending();
        };
    }

    public enum Result {
        APPROVED,
        DECLINED,
        /** 처리 중 · 결과 불명. 확정되면 결과 이벤트가 간다. */
        PENDING
    }
}
