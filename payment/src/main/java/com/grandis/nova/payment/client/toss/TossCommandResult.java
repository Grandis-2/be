package com.grandis.nova.payment.client.toss;

/**
 * 승인 · 취소 호출의 결과(D8). 예외를 던지지 않고 이 다섯 가지 중 하나를 돌려준다 — 부르는 쪽(NV-101 · 103)이 switch 로
 * 도메인 사건에 옮긴다. 결제 도메인(payment.domain)은 이 타입을 모른다.
 *
 * 가르는 기준은 HTTP 상태가 아니라 토스 코드다({@link TossErrorCatalog}).
 */
public sealed interface TossCommandResult {

    /** 토스가 처리했다(승인 = DONE, 취소 = CANCELED)고 응답했고, 물은 결제의 응답이다. */
    record Succeeded(TossPayment payment) implements TossCommandResult {
    }

    /** 토스가 처리하지 않았고, 같은 요청을 다시 보내도 바뀌지 않는다. message 는 토스 문구(사용자 노출 여부는 부르는 쪽) */
    record Rejected(String code, String message) implements TossCommandResult {
    }

    /** 토스가 처리하지 않았고, 시간이 지나 다시 보내면 달라질 수 있다. */
    record Transient(String code, String message) implements TossCommandResult {
    }

    /** 같은 요청이 토스에서 처리 중이다(409 IDEMPOTENT_REQUEST_PROCESSING · ALREADY_PROCESSING_REQUEST). 잠시 뒤 같은 키로 다시. */
    record Processing(String code) implements TossCommandResult {
    }

    /**
     * 처리됐는지 모른다. 실패로 단정하지 않는다(이중 청구 · 이중 환불 판단 위험). 푸는 방법은 사유가 정한다 — {@link #resolution()}:
     * 응답을 못 받았으면 같은 멱등 키로 재전송, 토스가 응답했는데 해석할 수 없으면 paymentKey 조회.
     *
     * @param code 토스 코드가 있으면 그 값, 전송 실패 · 읽을 수 없는 응답 · 성공 응답 이상이면 null
     */
    record Unknown(UnknownReason reason, String code) implements TossCommandResult {

        public UnknownReason.Resolution resolution() {
            return reason.resolution();
        }
    }
}
