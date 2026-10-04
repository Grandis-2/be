package com.grandis.nova.payment.refund;

import com.grandis.nova.payment.ClaimedTransaction;
import com.grandis.nova.payment.client.toss.TossCancelCodes;
import com.grandis.nova.payment.client.toss.TossCancelRequest;
import com.grandis.nova.payment.client.toss.TossCommandResult;
import com.grandis.nova.payment.client.toss.TossIdempotencyKey;
import com.grandis.nova.payment.client.toss.TossLookupResult;
import com.grandis.nova.payment.client.toss.TossPayment;
import com.grandis.nova.payment.client.toss.TossPaymentClient;
import com.grandis.nova.payment.client.toss.TossPaymentStatus;
import com.grandis.nova.payment.client.toss.UnknownReason;
import com.grandis.nova.payment.confirm.TossOutcomes;
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
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * 환불(REFUND)을 보내고, 결과를 모르면 사람 손 없이 확정한다. 첫 전송도 여기서 한다 — 환불 요청은 행만 열고(PENDING) 워커가 집는다.
 * 에픽 계획서 "결과 불명 해소 2단계 계약"의 취소 행을 따른다.
 *
 * <b>1단계 — 무엇으로 알아내나</b>(승인 복구와 같은 기준)
 * <ul>
 *   <li>같은 키로 전액 취소: 처음 보낸다 · 응답을 받지 못했다(타임아웃 · 연결 실패 · 반영 전에 멈춤 · 키 교체 직후 멈춤) · 이 키의 첫 요청이
 *       처리 중이다(409). 토스는 같은 키에 첫 응답을 돌려주므로 재전송이 곧 그 요청의 결과다.</li>
 *   <li>paymentKey 조회: 토스가 응답했는데 일시 오류 · 해석 불가 · 이미 취소됨이었다. 토스는 오류 응답도 키에 캐시하므로 같은 키로는
 *       같은 응답만 온다.</li>
 * </ul>
 * <b>2단계 — 조회 상태별 다음 행동</b>: CANCELED → 환불 완료(전액 · 금액 대조) / DONE → 받은 거절이 없으면 같은 행에서 키를 바꿔 재전송,
 * 있으면 그 거절로 실패 / 없음 · 그 밖의 상태 → 에스컬레이션(자동 확정 금지).
 *
 * <b>키 교체</b>는 "토스 안에 아직 처리 중일 수 있는 앞 키가 없다"가 성립할 때만 일어나게 한다. 조회로 이어지는 응답(일시 오류 · 해석할
 * 수 없는 불명)을 받으면 그 응답 뒤 {@link #SETTLE_GRACE} 를 기다린 다음 회차에야 조회 → 교체한다 — 일시 오류도 멱등 처리 앞 계층의
 * 오류(연속 요청 제한 등)이거나 캐시되지 않은 5xx 일 수 있어, "토스가 처리하지 않았다"를 증명하지 않는다. 그래서 교체한 키가 받은 거절은
 * 결론이다. 교체는 회차마다 한 번이다. 같은 키 처리 중(409)은 같은 키로 다시 보내므로 기다리지 않는다.
 * 그래도 남는 방어는 "토스는 같은 결제를 두 번 전액 취소하지 않는다(ALREADY_CANCELED_PAYMENT)" 이다 — 두 번째는 불명 → 조회 → CANCELED.
 *
 * <b>거절</b>도 바로 확정하지 않는다 — 조회가 CANCELED 면(앞 요청 · 상점 관리자 수동 취소) 돈은 이미 돌아갔다. 조회가 불명이면 거절 코드를
 * 행에 남겨 다음 회차가 키를 바꾸지 않고 그 사유로 확정하게 한다.
 *
 * <b>시각</b> — 환불에는 승인 창이 없다. 첫 전송부터 {@link #GIVE_UP_AFTER} 가 지나도 끝나지 않으면 에스컬레이션한다(상태 그대로,
 * 실패로 굳히지 않는다). 토스 장애 · 상점 설정 오류(취소에선 일시 오류)가 몇 시간 이어져도 스스로 끝나게 길게 둔다 — 돈은 늦게 돌아갈 뿐
 * 두 번 나가지 않는다. 결과를 모르는 응답도 리스(70초)를 그대로 두지 않고 재시도 시각을 둔다 — 일시 오류 · 처리 중 · 불명 · 조회 불명
 * 모두 시도마다 간격을 두 배로 늘린다({@link #retryAfter}). 상태는 RETRY_SCHEDULED 가 되지만 마지막 오류의 불명 사유로 푸는 방법은 그대로다.
 *
 * 확정 실패와 에스컬레이션은 ERROR 로 남긴다(경보). 결제 키는 로그 · 오류 칸에 싣지 않는다.
 */
@Component
class RefundRecovery implements RecoveryHandler {

    private static final Logger log = LoggerFactory.getLogger(RefundRecovery.class);

    /** 토스로 보내는 취소 사유(필수). 예약 취소 사유는 거래 행에 없다 — 사람이 볼 사유는 주문 이력에 있다. */
    static final String CANCEL_REASON = "주문 취소";
    /** 조회로 이어지는 응답(일시 오류 · 해석 불가) 뒤 키를 바꾸기 전 여유. 그 요청이 토스 안에서 끝날 시간(읽기 60초 · 리스 70초 + 처리 꼬리)이다. */
    static final Duration SETTLE_GRACE = Duration.ofMinutes(5);
    /** 첫 전송부터 이만큼 지나도 끝나지 않으면 사람이 본다. */
    static final Duration GIVE_UP_AFTER = Duration.ofHours(24);
    /** 일시 오류 · 처리 중 · 다시 조회의 첫 간격. 시도마다 두 배, {@link #MAX_RETRY_AFTER} 까지. */
    static final Duration FIRST_RETRY_AFTER = Duration.ofSeconds(10);
    static final Duration MAX_RETRY_AFTER = Duration.ofMinutes(10);
    static final String UNRESOLVED = "RECOVERY_UNRESOLVED";
    /** 환불 완료라는 응답이 전액 취소와 맞지 않는다(금액 · 잔액). 돈이 다르게 움직였을 수 있다. */
    static final String REFUND_MISMATCH = "REFUND_MISMATCH";
    /** 환불 완료라는 응답에 취소 시각이 없다 — 계약 위반. */
    static final String MISSING_CANCELED_AT = "MISSING_CANCELED_AT";

    private final TossPaymentClient toss;
    private final RefundSettlement settlement;
    private final Clock clock;

    RefundRecovery(TossPaymentClient toss, RefundSettlement settlement, Clock clock) {
        this.toss = toss;
        this.settlement = settlement;
        this.clock = clock;
    }

    @Override
    public TransactionType type() {
        return TransactionType.REFUND;
    }

    @Override
    public void recover(PaymentTransaction seen, ClaimedTransaction claimed) {
        PaymentTransaction held = claimed.transaction();
        if (!clock.instant().isBefore(held.requestedAt().plus(GIVE_UP_AFTER))) {
            escalate(claimed, new ProviderError(UNRESOLVED, "첫 전송 뒤 " + GIVE_UP_AFTER + " 동안 확정하지 못함"));
            return;
        }
        ProviderError last = seen.lastError();
        if (last == null || TossCancelCodes.isSameKeyInFlight(last.code())
                || TossOutcomes.resolutionOf(last) == UnknownReason.Resolution.RESEND) {
            send(claimed);
            return;
        }
        // 거절 코드: 앞 회차가 거절을 받고 다시 조회하기로 했다 — 그 거절을 이어 받는다
        lookup(claimed, TossCancelCodes.isRejection(last.code()) ? new Outcome.Rejected(last) : null);
    }

    /**
     * 거래의 지금 키로 전액 취소를 보낸다. 거절은 조회로 확인하고, 일시 오류 · 처리 중 · 불명은 간격을 두고 다음 회차가 본다.
     * 조회로 이어지는 응답(일시 오류 · 해석 불가)은 적어도 {@link #SETTLE_GRACE} 뒤다 — 그 요청이 아직 처리 중일 수 있어, 끝나기 전에
     * 키를 바꾸지 않게.
     */
    private void send(ClaimedTransaction claimed) {
        PaymentTransaction held = claimed.transaction();
        TossCommandResult result = toss.cancel(new TossCancelRequest(held.providerPaymentKey().value(), CANCEL_REASON),
                new TossIdempotencyKey(held.idempotencyKey().value()));
        if (result instanceof TossCommandResult.Succeeded succeeded) {
            confirm(claimed, succeeded.payment(), "취소");
            return;
        }
        switch (TossOutcomes.notSucceeded(result)) {
            case Outcome.Rejected rejected -> lookup(claimed, rejected);
            case Outcome.InProgress inProgress -> settle(claimed, new Outcome.InProgress(inProgress.error(),
                    TossCancelCodes.isSameKeyInFlight(inProgress.error().code())
                            ? retryAfter(held) : atLeast(retryAfter(held), SETTLE_GRACE)), "취소");
            case Outcome.Unknown unknown -> settle(claimed, new Outcome.InProgress(unknown.error(),
                    TossOutcomes.resolutionOf(unknown.error()) == UnknownReason.Resolution.LOOKUP
                            ? atLeast(retryAfter(held), SETTLE_GRACE) : retryAfter(held)), "취소");
            case Outcome.Confirmed confirmed -> throw new IllegalStateException("성공 아닌 응답이 확정이 됐다: " + held);
        }
    }

    /**
     * 조회 결과의 분류는 여기 한 곳이다.
     *
     * @param rejection 받은 거절(이번 회차 또는 앞 회차가 행에 남긴 것). 있으면 키를 바꾸지 않고, 실패로 확정할 때 이 거절을 쓴다
     */
    private void lookup(ClaimedTransaction claimed, Outcome.Rejected rejection) {
        PaymentTransaction held = claimed.transaction();
        switch (toss.findByPaymentKey(held.providerPaymentKey().value())) {
            case TossLookupResult.Found found -> found(claimed, found.payment(), rejection);
            // 환불할 결제가 토스에 없다 — 우리 기록과 결제사가 어긋났다
            case TossLookupResult.NotFound notFound -> escalate(claimed,
                    new ProviderError(TossOutcomes.LOOKUP + "NOT_FOUND", "환불할 결제가 결제사에 없다"));
            case TossLookupResult.Unknown unknown ->
                    settle(claimed, new Outcome.InProgress(lookupUnknown(unknown, rejection), retryAfter(held)), "조회");
        }
    }

    private void found(ClaimedTransaction claimed, TossPayment payment, Outcome.Rejected rejection) {
        switch (payment.status()) {
            case CANCELED -> confirm(claimed, payment, "조회");
            case DONE -> notCanceled(claimed, rejection);
            case READY, IN_PROGRESS, WAITING_FOR_DEPOSIT, PARTIAL_CANCELED, ABORTED, EXPIRED, UNRECOGNIZED ->
                    escalate(claimed, new ProviderError(TossOutcomes.LOOKUP + payment.status().name(),
                            "자동 확정하지 않는 조회 상태"));
        }
    }

    /** 토스가 아직 취소하지 않았다. 거절을 받지 않았으면 새 키로 다시 보내고, 받았으면 그 거절로 확정한다. */
    private void notCanceled(ClaimedTransaction claimed, Outcome.Rejected rejection) {
        PaymentTransaction held = claimed.transaction();
        if (rejection == null) {
            log.info("환불 복구 — 미처리 확인, 멱등 키를 바꿔 재전송 transactionId={} target={}", held.id(), held.target());
            send(settlement.rotateIdempotencyKey(claimed));
            return;
        }
        settle(claimed, rejection, "조회");
        log.error("환불 실패 — 사람 확인 필요 transactionId={} target={} attempts={} code={}",
                held.id(), held.target(), held.attemptCount(), rejection.error().code());
    }

    private void confirm(ClaimedTransaction claimed, TossPayment payment, String how) {
        Outcome outcome = refunded(payment, claimed.transaction());
        if (outcome instanceof Outcome.Unknown unknown) {
            // 다시 물어도 같다. 사람이 본다
            escalate(claimed, unknown.error());
            return;
        }
        settle(claimed, outcome, how);
    }

    /** 환불 완료라는 응답도 전액 취소(잔액 0 · 결제 금액 그대로)이고 취소 시각이 있어야 완료다. 아니면 불명으로 두고 사람이 본다. */
    static Outcome refunded(TossPayment payment, PaymentTransaction held) {
        long amount = held.amount().amount().longValueExact();
        if (payment.status() != TossPaymentStatus.CANCELED || payment.totalAmount() != amount
                || payment.balanceAmount() != 0) {
            log.error("토스 환불 응답이 전액 취소와 맞지 않다 — 결과 불명으로 둔다 transactionId={} status={} totalAmount={} "
                    + "balanceAmount={} amount={}", held.id(), payment.status(), payment.totalAmount(),
                    payment.balanceAmount(), amount);
            return new Outcome.Unknown(new ProviderError(REFUND_MISMATCH, UnknownReason.MISMATCHED_RESPONSE.name()));
        }
        Optional<Instant> canceledAt = payment.cancels().stream()
                .map(TossPayment.Cancel::canceledAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder());
        if (canceledAt.isEmpty()) {
            log.error("토스 환불 응답에 취소 시각이 없다 — 결과 불명으로 둔다 transactionId={}", held.id());
            return new Outcome.Unknown(new ProviderError(MISSING_CANCELED_AT, UnknownReason.UNREADABLE_RESPONSE.name()));
        }
        return new Outcome.Confirmed(canceledAt.get());
    }

    /**
     * 조회도 불명이다 — 다음 회차가 다시 조회한다(문구가 불명 사유 이름이 아니라 재전송으로 바뀌지 않는다).
     * 받은 거절은 남긴다 — 조회 불명 한 번에 사라지면 다음 회차가 키를 바꾸고 원래 사유를 잃는다.
     */
    private static ProviderError lookupUnknown(TossLookupResult.Unknown unknown, Outcome.Rejected rejection) {
        String reason = TossOutcomes.LOOKUP + unknown.reason().name();
        if (rejection != null) {
            return new ProviderError(rejection.error().code(), reason);
        }
        return new ProviderError(
                TossOutcomes.LOOKUP + (unknown.code() == null ? unknown.reason().name() : unknown.code()), reason);
    }

    private static Duration atLeast(Duration delay, Duration floor) {
        return delay.compareTo(floor) < 0 ? floor : delay;
    }

    /** 시도마다 두 배(첫 {@link #FIRST_RETRY_AFTER}, 최대 {@link #MAX_RETRY_AFTER}). 시도 횟수는 선점마다 오른다. */
    static Duration retryAfter(PaymentTransaction held) {
        int doublings = Math.min(Math.max(held.attemptCount(), 1) - 1, 16);
        Duration delay = FIRST_RETRY_AFTER.multipliedBy(1L << doublings);
        return delay.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : delay;
    }

    private void settle(ClaimedTransaction claimed, Outcome outcome, String how) {
        settlement.settle(claimed, outcome);
        PaymentTransaction held = claimed.transaction();
        log.info("환불 복구 반영 transactionId={} target={} 방법={} outcome={}", held.id(), held.target(), how,
                outcome.getClass().getSimpleName());
    }

    private void escalate(ClaimedTransaction claimed, ProviderError error) {
        settlement.escalate(claimed, error);
        PaymentTransaction held = claimed.transaction();
        log.error("결제 복구 중단 — 사람 확인 필요(자동 확정하지 않음) type=REFUND transactionId={} target={} attempts={} code={}",
                held.id(), held.target(), held.attemptCount(), error.code());
    }
}
