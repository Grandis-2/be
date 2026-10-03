package com.grandis.nova.payment.confirm;

import com.grandis.nova.payment.ClaimedTransaction;
import com.grandis.nova.payment.client.toss.TossCommandResult;
import com.grandis.nova.payment.client.toss.TossConfirmCodes;
import com.grandis.nova.payment.client.toss.TossConfirmRequest;
import com.grandis.nova.payment.client.toss.TossIdempotencyKey;
import com.grandis.nova.payment.client.toss.TossLookupResult;
import com.grandis.nova.payment.client.toss.TossPayment;
import com.grandis.nova.payment.client.toss.TossPaymentClient;
import com.grandis.nova.payment.client.toss.UnknownReason;
import com.grandis.nova.payment.domain.enums.TransactionType;
import com.grandis.nova.payment.domain.model.Outcome;
import com.grandis.nova.payment.domain.model.PaymentTransaction;
import com.grandis.nova.payment.recovery.RecoveryHandler;
import com.grandis.nova.payment.vo.ProviderError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 결과를 확정하지 못한 승인(CAPTURE)을 사람 손 없이 확정한다. 에픽 계획서 "결과 불명 해소 2단계 계약"을 따른다.
 *
 * <b>시각 기준 하나</b> — 모든 판단은 첫 전송 시각(requested_at)에서 잰다. 토스는 인증 뒤 10분 안의 승인만 받고 인증은 첫 전송 전이므로:
 * <ul>
 *   <li>열림(첫 전송 + 10분 전): 새 키로 다시 보낼 수 있다.</li>
 *   <li>정산 중(그 뒤 + 5분): 새 요청은 받아들여지지 않지만, 창 안에 보낸 마지막 요청이 토스 안에서 아직 처리 중일 수 있다(읽기 60초 ·
 *       리스 70초 · 토스 처리 꼬리). 조회만 하고 실패로 확정하지 않는다.</li>
 *   <li>닫힘(정산 기한 뒤): 아직 승인되지 않은 결제는 앞으로도 승인될 수 없다 — 실패로 확정한다.</li>
 *   <li>첫 전송 + 1시간이 지나도 끝나지 않으면(조회가 계속 불명) 에스컬레이션한다. 횟수가 아니라 시각이라, 창 안에서 스스로 끝나는 반복에는
 *       걸리지 않는다.</li>
 * </ul>
 *
 * <b>1단계 — 무엇으로 알아내나</b>
 * <ul>
 *   <li>같은 키 재전송: 응답을 받지 못했다(타임아웃 · 연결 실패 · 반영 전에 멈춤 · 키 교체 직후 멈춤) 또는 이 키의 첫 요청이 처리 중(409)이다.
 *       토스는 같은 키에 첫 응답을 돌려주므로 재전송이 곧 그 요청의 결과다. 창 밖에서도 무해하다 — 처리된 적 없는 요청이면 토스가 세션 만료로
 *       거절한다.</li>
 *   <li>paymentKey 조회: 토스가 응답했는데 해석할 수 없었거나 일시 오류 · 결제 단위 "처리 중"이었다. 같은 키로는 같은 응답만 온다 —
 *       토스는 오류 응답도 키에 캐시한다(2026-10-03 샌드박스 확인).</li>
 * </ul>
 * <b>2단계 — 조회 상태별 다음 행동</b>: DONE → 승인(금액 · 승인 시각 대조) / ABORTED · EXPIRED → 실패 /
 * READY · IN_PROGRESS · 없음 → 열림이면 같은 행에서 키를 바꿔 재전송, 정산 중이면 다시 조회, 닫힘이면 실패 /
 * CANCELED · PARTIAL_CANCELED · WAITING_FOR_DEPOSIT · 모르는 상태 → 에스컬레이션(자동 확정 금지).
 *
 * <b>키 교체</b>는 "토스 안에 진행 중인 우리 요청이 없다"가 성립할 때만 한다: 직전 결과가 토스의 응답(일시 오류 · 해석 불가)이고, 조회가
 * 미처리를 보였고, 결제 단위 "처리 중"이 아니고, 거절을 받은 적이 없다. 교체는 리스를 새로 잡고 마지막 오류를 지운다 — 새 키 전송이
 * 리스 안에 들고, 교체 뒤 멈추면 다음 작업자는 같은(새) 키로 이어 간다.
 *
 * <b>거절</b>도 바로 확정하지 않는다 — 복구가 받은 거절은 (새 키든 같은 키든) 조회로 확인하고, 조회 결과는 위 2단계 규칙 한 곳에서
 * 가른다. 그 키의 거절이 결제의 결론은 아니다: 같은 결제의 앞선 요청이 토스 안에서 아직 처리 중이면 그 요청이 승인할 수 있다.
 * 다시 조회하게 되면 거절 코드를 행에 남긴다 — 다음 회차도 키를 바꾸지 않고, 닫힘에서 그 거절 사유로 확정한다.
 * 결제 키 조회의 "없음"(404)도 미처리와 같은 규칙이다 — 처리 중인 결제가 잠시 404 로 보이더라도 정산 기한 전에는 실패로 확정하지 않는다.
 * 그래도 남는 방어는 "토스는 같은 paymentKey 를 두 번 승인하지 않는다(ALREADY_PROCESSED_PAYMENT)" 는 문서상 계약이다(실결제 미확인).
 *
 * 에스컬레이션은 상태를 그대로 두고 ERROR 로 남긴다 — 실패로 굳히지 않는다. 그 대상은 계속 막혀 있다(모르는 채 다시 청구하지 않는다).
 * 결제 키는 로그 · 오류 칸에 싣지 않는다.
 */
@Component
class CaptureRecovery implements RecoveryHandler {

    private static final Logger log = LoggerFactory.getLogger(CaptureRecovery.class);

    /** 토스 승인 창(결제 인증 뒤 10분). 첫 전송은 인증 뒤이므로 첫 전송 + 이 값이 지나면 확실히 창 밖이다. */
    static final Duration APPROVAL_WINDOW = Duration.ofMinutes(10);
    /** 창 안에 보낸 마지막 요청이 끝날 여유. 읽기 60초 · 리스 70초를 덮고 토스 쪽 처리 꼬리를 더한 판단값이다. */
    static final Duration SETTLE_GRACE = Duration.ofMinutes(5);
    /** 첫 전송부터 이만큼 지나도 끝나지 않으면 사람이 본다. 정산 기한(15분) 뒤의 조회 불명이 45분 이어진 것이다. */
    static final Duration GIVE_UP_AFTER = Duration.ofHours(1);
    /** 정산 중 · 결제 단위 처리 중에 다시 조회할 때까지. */
    static final Duration RECHECK_AFTER = Duration.ofSeconds(30);
    static final String UNRESOLVED = "RECOVERY_UNRESOLVED";
    static final String ORDER_MISMATCH = TossOutcomes.LOOKUP + "ORDER_MISMATCH";

    private final TossPaymentClient toss;
    private final CaptureSettlement settlement;
    private final Clock clock;

    CaptureRecovery(TossPaymentClient toss, CaptureSettlement settlement, Clock clock) {
        this.toss = toss;
        this.settlement = settlement;
        this.clock = clock;
    }

    @Override
    public TransactionType type() {
        return TransactionType.CAPTURE;
    }

    @Override
    public void recover(PaymentTransaction seen, ClaimedTransaction claimed) {
        PaymentTransaction held = claimed.transaction();
        Instant now = clock.instant();
        if (!now.isBefore(held.requestedAt().plus(GIVE_UP_AFTER))) {
            escalate(claimed, new ProviderError(UNRESOLVED, "첫 전송 뒤 " + GIVE_UP_AFTER + " 동안 확정하지 못함"));
            return;
        }
        Phase phase = phase(held, now);
        ProviderError last = seen.lastError();
        if (last == null || TossConfirmCodes.isSameKeyInFlight(last.code())
                || TossOutcomes.resolutionOf(last) == UnknownReason.Resolution.RESEND) {
            resend(claimed, phase);
            return;
        }
        // 결제 단위 "처리 중": 우리 요청 하나가 토스 안에 있다 — 끝날 때까지 새 키로 보내지 않는다.
        // 거절 코드: 앞 회차가 거절을 받고 다시 조회하기로 했다 — 그 거절을 이어 받는다
        ProviderError keep = TossConfirmCodes.isOtherRequestInFlight(last.code()) ? last : null;
        Outcome.Rejected rejection = TossConfirmCodes.isRejection(last.code()) ? new Outcome.Rejected(last) : null;
        lookup(claimed, new Check(phase, keep, rejection));
    }

    private Phase phase(PaymentTransaction held, Instant now) {
        Instant windowEnd = held.requestedAt().plus(APPROVAL_WINDOW);
        if (now.isBefore(windowEnd)) {
            return Phase.OPEN;
        }
        return now.isBefore(windowEnd.plus(SETTLE_GRACE)) ? Phase.SETTLING : Phase.CLOSED;
    }

    /**
     * 거래의 지금 키로 보낸다. 거절은 바로 확정하지 않고 조회로 확인한다 — 그 키가 받은 거절(캐시된 것일 수 있다)이 결제의 결론은 아니다.
     * 같은 결제의 앞선 요청이 토스 안에서 아직 처리 중이면 그 요청이 승인할 수 있다. 처리 중 · 일시 오류는 {@link #RECHECK_AFTER} 뒤에
     * 다시 본다(같은 키 재전송 · 키 교체가 10초마다 몰리지 않게).
     */
    private void resend(ClaimedTransaction claimed, Phase phase) {
        PaymentTransaction held = claimed.transaction();
        TossConfirmRequest request = new TossConfirmRequest(held.providerPaymentKey().value(),
                held.providerOrderId().value(), held.amount().amount().longValueExact());
        TossCommandResult result = toss.confirm(request, new TossIdempotencyKey(held.idempotencyKey().value()));
        Outcome outcome = TossOutcomes.of(result, held);
        if (result instanceof TossCommandResult.Succeeded && outcome instanceof Outcome.Unknown unknown) {
            // 승인됐다는데 금액 · 승인 시각이 맞지 않는다 — 다시 물어도 같다. 사람이 본다
            escalate(claimed, unknown.error());
            return;
        }
        switch (outcome) {
            case Outcome.Rejected rejected -> lookup(claimed, new Check(phase, null, rejected));
            case Outcome.InProgress inProgress ->
                    settle(claimed, new Outcome.InProgress(inProgress.error(), RECHECK_AFTER), "재전송");
            case Outcome.Confirmed confirmed -> settle(claimed, confirmed, "재전송");
            case Outcome.Unknown unknown -> settle(claimed, unknown, "재전송");
        }
    }

    /**
     * 조회 결과의 분류는 여기 한 곳이다 — 처음 조회든, 거절을 확인하는 조회든 같은 단계 규칙을 탄다.
     *
     * @param phase     지금 단계
     * @param keepError null 이 아니면 키를 바꾸지 않는다(결제 단위 처리 중). 다시 조회할 때 이 코드를 남겨 다음 회차도 바꾸지 않게 한다
     * @param rejection 받은 거절(이번 회차 또는 앞 회차가 행에 남긴 것). 실패로 확정할 때 이 거절을 쓴다
     */
    private record Check(Phase phase, ProviderError keepError, Outcome.Rejected rejection) {

        Outcome.Rejected failure(String what) {
            return rejection != null ? rejection : TossOutcomes.lookedUpFailure(what);
        }

        /** 거절을 받았거나 결제 단위 처리 중이면 키를 바꾸지 않는다 — 우리 요청 하나가 아직 토스 안에 있을 수 있다. */
        boolean mayRotate() {
            return keepError == null && rejection == null;
        }

        /**
         * 행에 남길 오류. 표시(교체 금지 · 받은 거절)가 있으면 그 코드를 코드 칸에 남기고 문구에 이번 조회 결과를 적는다 — 다음 회차가
         * 표시를 이어 받는다. 표시를 남기는 곳은 여기 하나다(다시 조회 · 조회 불명이 함께 쓴다).
         *
         * @return 표시가 없으면 null
         */
        ProviderError carry(String looked) {
            if (keepError != null) {
                return new ProviderError(keepError.code(), looked);
            }
            if (rejection != null) {
                return new ProviderError(rejection.error().code(), looked);
            }
            return null;
        }
    }

    private void lookup(ClaimedTransaction claimed, Check check) {
        PaymentTransaction held = claimed.transaction();
        switch (toss.findByPaymentKey(held.providerPaymentKey().value())) {
            case TossLookupResult.Found found -> found(claimed, found.payment(), check);
            case TossLookupResult.NotFound notFound -> unprocessed(claimed, check, "NOT_FOUND");
            case TossLookupResult.Unknown unknown -> settle(claimed, lookupUnknown(unknown, check), "조회");
        }
    }

    private void found(ClaimedTransaction claimed, TossPayment payment, Check check) {
        PaymentTransaction held = claimed.transaction();
        if (!held.providerOrderId().value().equals(payment.orderId())) {
            // 결제 키로 찾은 결제가 이 결제창의 것이 아니다 — 다른 결제의 결과를 붙이지 않는다
            escalate(claimed, new ProviderError(ORDER_MISMATCH, "조회한 결제의 orderId 가 다르다"));
            return;
        }
        switch (payment.status()) {
            case DONE -> approve(claimed, payment);
            case ABORTED, EXPIRED -> settle(claimed, check.failure(payment.status().name()), "조회");
            case READY, IN_PROGRESS -> unprocessed(claimed, check, payment.status().name());
            case CANCELED, PARTIAL_CANCELED, WAITING_FOR_DEPOSIT, UNRECOGNIZED -> escalate(claimed, new ProviderError(
                    TossOutcomes.LOOKUP + payment.status().name(), "자동 확정하지 않는 조회 상태"));
        }
    }

    /** 토스가 아직 승인하지 않았다. 열림이면 새 키로 다시 보내고, 정산 중이면 다시 조회하고, 닫힘이면 실패로 확정한다. */
    private void unprocessed(ClaimedTransaction claimed, Check check, String what) {
        PaymentTransaction held = claimed.transaction();
        if (check.phase() == Phase.CLOSED) {
            settle(claimed, check.failure(what), "조회");
            return;
        }
        if (check.phase() == Phase.SETTLING || !check.mayRotate()) {
            recheck(claimed, check, what);
            return;
        }
        log.info("결제 복구 — 미처리 확인, 멱등 키를 바꿔 재전송 transactionId={} orderId={} 조회={}",
                held.id(), held.providerOrderId().value(), what);
        resend(settlement.rotateIdempotencyKey(claimed), check.phase());
    }

    private void recheck(ClaimedTransaction claimed, Check check, String what) {
        String looked = TossOutcomes.LOOKUP + what;
        ProviderError carried = check.carry(looked);
        ProviderError error = carried != null ? carried : new ProviderError(looked, "조회 결과 미처리 — 다시 조회");
        settle(claimed, new Outcome.InProgress(error, RECHECK_AFTER), "조회");
    }

    private void approve(ClaimedTransaction claimed, TossPayment payment) {
        Outcome outcome = TossOutcomes.approved(payment, claimed.transaction());
        if (outcome instanceof Outcome.Unknown unknown) {
            escalate(claimed, unknown.error());
            return;
        }
        settle(claimed, outcome, "조회");
    }

    /**
     * 조회도 불명이다. 그대로 두고(리스가 끝나면) 다시 조회한다 — 문구가 불명 사유가 아니라 재전송으로 바뀌지 않는다.
     * 표시(교체 금지 · 받은 거절)는 남긴다 — 조회 불명 한 번에 표시가 사라지면 다음 회차가 키를 바꾸고, 닫힘에서 원래 사유를 잃는다.
     */
    private static Outcome.Unknown lookupUnknown(TossLookupResult.Unknown unknown, Check check) {
        String reason = TossOutcomes.LOOKUP + unknown.reason().name();
        ProviderError carried = check.carry(reason);
        if (carried != null) {
            return new Outcome.Unknown(carried);
        }
        return new Outcome.Unknown(new ProviderError(
                TossOutcomes.LOOKUP + (unknown.code() == null ? unknown.reason().name() : unknown.code()), reason));
    }

    private void settle(ClaimedTransaction claimed, Outcome outcome, String how) {
        settlement.settle(claimed, outcome);
        PaymentTransaction held = claimed.transaction();
        log.info("결제 복구 반영 transactionId={} orderId={} 방법={} outcome={}", held.id(),
                held.providerOrderId().value(), how, outcome.getClass().getSimpleName());
    }

    private void escalate(ClaimedTransaction claimed, ProviderError error) {
        settlement.escalate(claimed, error);
        PaymentTransaction held = claimed.transaction();
        log.error("결제 복구 중단 — 사람 확인 필요(자동 확정하지 않음) transactionId={} orderId={} target={} attempts={} code={}",
                held.id(), held.providerOrderId().value(), held.target(), held.attemptCount(), error.code());
    }

    private enum Phase {
        /** 새 키로 다시 보낼 수 있다. */
        OPEN,
        /** 새 요청은 안 되지만 마지막 요청이 아직 처리 중일 수 있다 — 조회만. */
        SETTLING,
        /** 승인되지 않은 결제는 앞으로도 승인될 수 없다. */
        CLOSED
    }
}
