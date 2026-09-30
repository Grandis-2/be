package com.grandis.nova.payment.domain.enums;

import com.grandis.nova.payment.domain.model.Outcome;

import java.util.Optional;

/**
 * 결제 거래 상태와 그 전이. 전이는 세 갈래다.
 * <ul>
 *   <li>시작({@link #start}) — 사용자의 승인 요청이 CAPTURE 를 연다(PENDING → PROCESSING).</li>
 *   <li>선점({@link #claim}) — 워커가 재전송하려고 집는다. CAPTURE PENDING 은 집지 않는다(사용자가 결제창을 버렸을 수 있다).
 *       PROCESSING 은 리스가 만료된 것만, RETRY_SCHEDULED 는 재시도 시각이 된 것만 — 시각 조건은 저장소가 DB 시각으로 본다.</li>
 *   <li>반영({@link #resolve}) — 결제사 결과를 PROCESSING 에만 반영한다.</li>
 * </ul>
 * 빈 값은 "받지 않는다" 다. 시간이 지났다는 이유만으로 가는 전이는 없다 — FAILED 는 확정 실패({@link Outcome.Rejected})뿐이다.
 * 결과 불명 · 처리 중을 실패로 단정하면 응답을 잃은 성공 결제를 버리게 된다.
 */
public enum TransactionStatus {

    /** 행을 열었고 아직 결제사에 보내지 않았다. */
    PENDING,
    /** 결제사에 보냈다(또는 보내는 중). 리스를 쥔 작업자만 결과를 반영한다. */
    PROCESSING,
    /** 결제사가 처리 중(409)이거나 일시 오류다. 재시도 시각에 다시 보낸다. */
    RETRY_SCHEDULED,
    SUCCEEDED,
    /** 결제사 응답 · 조회로 실패가 확정됐다. */
    FAILED;

    public boolean isFinished() {
        return this == SUCCEEDED || this == FAILED;
    }

    public Optional<TransactionStatus> start(TransactionType type) {
        return this == PENDING && type == TransactionType.CAPTURE ? Optional.of(PROCESSING) : Optional.empty();
    }

    public Optional<TransactionStatus> claim(TransactionType type) {
        return switch (this) {
            case PENDING -> type == TransactionType.REFUND ? Optional.of(PROCESSING) : Optional.empty();
            case RETRY_SCHEDULED, PROCESSING -> Optional.of(PROCESSING);
            case SUCCEEDED, FAILED -> Optional.empty();
        };
    }

    public Optional<TransactionStatus> resolve(Outcome outcome) {
        if (this != PROCESSING) {
            return Optional.empty();
        }
        return Optional.of(switch (outcome) {
            case Outcome.Confirmed confirmed -> SUCCEEDED;
            case Outcome.Rejected rejected -> FAILED;
            case Outcome.InProgress inProgress -> RETRY_SCHEDULED;
            // 리스를 그대로 둔다. 만료되면 워커가 같은 멱등 키로 다시 보내 결과를 확정한다.
            case Outcome.Unknown unknown -> PROCESSING;
        });
    }
}
