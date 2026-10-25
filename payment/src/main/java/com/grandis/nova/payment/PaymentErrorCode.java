package com.grandis.nova.payment;

import com.grandis.nova.common.ErrorCode;

/**
 * payment 내부 API 가 던지는 업무 오류. 호출자(order · draw)가 이 이름으로 가른다. 승인 요청에서는 모두 시작 전에만 난다.
 *
 * 대상을 결제 전으로 되돌려도 되는 것은 "이 결제창은 앞으로도 시작될 수 없다" 는 코드뿐이다 — PAYMENT_ATTEMPT_NOT_FOUND ·
 * PAYMENT_AMOUNT_MISMATCH(결제창의 대상 · 금액은 바뀌지 않는다). 그 밖의 거절(교착 · 인증 · 모양)은
 * 이번 요청에 대한 것이라, 앞선 요청이 같은 결제창을 이미 시작했을 수 있다.
 * DEAD_LETTER_* 는 관리자 DLQ API 의 것이다. 이름 · 문구는 preorder 의 같은 코드와 같고, order 가 그대로 관리자에게 넘긴다.
 * REFUND_* 는 관리자 환불 조치 API 의 것이다. order 가 같은 이름 · 상태로 관리자에게 넘긴다 — 아무것도 바꾸지 않고 거절한다.
 */
public enum PaymentErrorCode implements ErrorCode {

    /** 없는 결제사 주문 번호 · 다른 대상의 번호. 남의 거래를 떠볼 수 없게 둘을 가르지 않는다. */
    PAYMENT_ATTEMPT_NOT_FOUND(404, "결제 시도를 찾을 수 없습니다."),
    DEAD_LETTER_NOT_FOUND(404, "DLQ 메시지를 찾을 수 없습니다."),
    /** 없거나 보관 시간(하루)이 지난 일괄 되돌리기. preorder REPROCESS_BATCH_NOT_FOUND 와 같은 자리다. */
    REDRIVE_BATCH_NOT_FOUND(404, "일괄 되돌리기를 찾을 수 없습니다."),
    /** 주문에 결제 · 환불 거래가 없다. */
    REFUND_NOT_FOUND(404, "환불을 찾을 수 없습니다."),
    /** 결제창을 열 때의 금액이 호출자의 저장 금액과 다르다(D15). */
    PAYMENT_AMOUNT_MISMATCH(409, "결제 금액이 주문 금액과 다릅니다."),
    /** 같은 대상의 동시 승인 시작과 교착했고 다시 해도 풀리지 않았다(D14). 이번 요청은 시작하지 않았다 — 잠시 뒤 다시 부른다. */
    PAYMENT_START_CONFLICT(409, "잠시 후 다시 시도해 주세요."),
    /** 지금 코드로도 읽지 못하는 원문이거나, 되돌리기를 기다리는 상태가 아니다. details.reason 에 까닭을 싣는다. */
    DEAD_LETTER_NOT_REDRIVABLE(409, "되돌릴 수 없는 메시지입니다."),
    /** OPEN 이 아니다. details.reason 에 지금 상태를 싣는다. */
    DEAD_LETTER_NOT_DISCARDABLE(409, "버릴 수 없는 메시지입니다."),
    /** 결제가 이미 환불로 끝났다. */
    REFUND_ALREADY_COMPLETED(409, "이미 환불된 결제입니다."),
    /** 마지막 환불이 아직 진행 중이다(복구 작업이 다루는 중) — 끝나거나 멈출 때까지 기다린다. */
    REFUND_IN_PROGRESS(409, "환불이 아직 진행 중입니다."),
    /** 마지막 환불이 관리자가 본 것과 다르다 — 그사이 다른 조치 · 결과 반영이 끼었다. details.currentTransactionId 에 지금 것을 싣는다. */
    REFUND_STATE_CHANGED(409, "환불 상태가 바뀌었습니다. 목록을 다시 확인해 주세요."),
    /** 다시 보내도 같거나 이중 환불이 될 수 있다. details.reason 에 까닭(멈춘 사유 · 결제사 상태)을 싣는다. */
    REFUND_NOT_RETRYABLE(409, "다시 시도할 수 없는 환불입니다."),
    /** 결제사 조회가 전액 취소가 아니다. details 에 결제사 상태 · 처음 금액 · 잔액 · 취소 건수를 싣는다(결제 키는 싣지 않는다). */
    REFUND_NOT_CANCELED_AT_PROVIDER(409, "결제사에서 전액 취소가 확인되지 않습니다."),
    /** 결제사 조회 결과를 몰랐거나 동시 조치 상한이다(details.reason=TOO_MANY_CONCURRENT) — 아무것도 바꾸지 않았다. 잠시 뒤 다시 한다. */
    REFUND_PROVIDER_UNAVAILABLE(503, "결제사 조회에 실패했습니다. 잠시 후 다시 시도해 주세요.");

    private final int status;
    private final String message;

    PaymentErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String defaultMessage() {
        return message;
    }
}
