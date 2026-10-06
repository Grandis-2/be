package com.grandis.nova.payment.client.toss;

/**
 * 결과를 알 수 없는 까닭과, 승인 · 취소에서 그것을 푸는 방법({@link Resolution}). 사유마다 푸는 방법이 생성자에 박혀 있다 —
 * 사유를 늘리면 방법도 반드시 정해야 컴파일된다. 복구(NV-102)는 {@link TossCommandResult.Unknown#resolution()} 을 switch 한다.
 *
 * 가르는 기준은 "토스가 응답을 했는가" 다. 토스는 같은 멱등 키의 재요청에 첫 응답을 그대로 돌려준다(성공 응답 확실, 오류 응답은
 * 미확인). 그래서 토스가 이미 응답한 경우(표의 불명 코드 · 예상 밖 상태 · 짝 불일치 · 읽을 수 없는 본문)는 같은 키로 다시 보내도
 * 같은 응답이 와서 끝나지 않는다 — 조회로 확정한다. 응답을 받지 못한 경우(타임아웃 · 연결 실패)만 같은 키 재전송으로 풀린다.
 *
 * 조회(TossLookupResult.Unknown)에서는 방법을 쓰지 않는다 — 조회는 아무것도 처리하지 않으므로 언제나 "나중에 다시 조회" 다.
 */
public enum UnknownReason {
    /** 읽기 · 연결 타임아웃. 토스가 처리했을 수 있다 */
    TIMEOUT(Resolution.RESEND),
    /** 연결 실패 · 끊김 등 입출력 오류 */
    CONNECTION_FAILURE(Resolution.RESEND),
    /**
     * 응답을 읽을 수 없다 — 2xx 본문이 계약과 다름, 코드 없는 오류 응답(게이트웨이 페이지 등). 응답이 토스의 것이면 같은 키로
     * 캐시된 같은 본문이 다시 오므로 조회로 푼다(게이트웨이 오류였어도 조회로 풀린다)
     */
    UNREADABLE_RESPONSE(Resolution.LOOKUP),
    /** 분류표에 "결과 불명"으로 적힌 코드(예: ALREADY_PROCESSED_PAYMENT) */
    LISTED_CODE(Resolution.LOOKUP),
    /** 분류표에 없는 코드. WARN 로그 — 표에 추가할 후보다 */
    UNLISTED_CODE(Resolution.LOOKUP),
    /** 2xx 인데 기대한 상태가 아니다(승인인데 DONE 아님 · 취소인데 CANCELED 아님, D7) */
    UNEXPECTED_STATUS(Resolution.LOOKUP),
    /** 2xx 인데 물은 결제(paymentKey · orderId)와 다른 응답 */
    MISMATCHED_RESPONSE(Resolution.LOOKUP);

    /** 승인 · 취소의 결과 불명을 푸는 방법. */
    public enum Resolution {
        /** 같은 요청을 같은 멱등 키로 다시 보낸다 — 토스가 첫 요청을 처리했으면 그 응답을, 아니면 새로 처리한 응답을 준다 */
        RESEND,
        /**
         * {@link TossPaymentClient#findByPaymentKey} 로 결제 상태를 본다. orderId 조회는 쓰지 않는다 — 승인된 결제만 찾아서
         * 처리됐지만 실패한 결제(ABORTED · EXPIRED)가 "없음" 으로 보이고, 복구가 끝나지 않는다.
         *
         * 조회는 1단계(무엇으로 알아내나)일 뿐이다. 본 상태로 무엇을 할지(2단계)는 에픽 계획서 §2 "결과 불명 해소 2단계 계약"
         * (명령 × 조회 상태 → 다음 행동)을 따르고 NV-102 · 103 이 테스트로 고정한다. 요지:
         * - 요청이 토스에 닿지 않은 것으로 보이면(승인인데 IN_PROGRESS, 취소인데 DONE) 같은 요청을 다시 보낸다 — 게이트웨이 오류처럼
         *   응답이 토스의 것이 아니었을 수 있다. "나중에 다시 조회" 만 반복하면 승인은 창을 넘겨 만료되고 환불은 시작되지 않는다.
         * - 승인 재전송은 승인 창(인증 후 10분) 안에서만 한다. 창 밖의 READY · IN_PROGRESS 는 실패 확정이다.
         * - 조회 결과만으로 자동 확정하지 않는 상태가 있다(승인의 CANCELED · WAITING_FOR_DEPOSIT · UNRECOGNIZED 등) — 알림 대상이다.
         * - 재전송 키(같은 키 / 같은 행에서 키 교체)도 그 계약을 따른다.
         */
        LOOKUP
    }

    private final Resolution resolution;

    UnknownReason(Resolution resolution) {
        this.resolution = resolution;
    }

    public Resolution resolution() {
        return resolution;
    }
}
