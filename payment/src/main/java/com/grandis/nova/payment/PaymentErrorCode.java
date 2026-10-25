package com.grandis.nova.payment;

import com.grandis.nova.common.ErrorCode;

/**
 * payment 내부 API 가 던지는 업무 오류. 호출자(order · draw)가 이 이름으로 가른다. 승인 요청에서는 모두 시작 전에만 난다.
 *
 * 대상을 결제 전으로 되돌려도 되는 것은 "이 결제창은 앞으로도 시작될 수 없다" 는 코드뿐이다 — PAYMENT_ATTEMPT_NOT_FOUND ·
 * PAYMENT_AMOUNT_MISMATCH(결제창의 대상 · 금액은 바뀌지 않는다). 그 밖의 거절(교착 · 인증 · 모양)은
 * 이번 요청에 대한 것이라, 앞선 요청이 같은 결제창을 이미 시작했을 수 있다.
 * DEAD_LETTER_* 는 관리자 DLQ API 의 것이다. 이름 · 문구는 preorder 의 같은 코드와 같고, order 가 그대로 관리자에게 넘긴다.
 */
public enum PaymentErrorCode implements ErrorCode {

    /** 없는 결제사 주문 번호 · 다른 대상의 번호. 남의 거래를 떠볼 수 없게 둘을 가르지 않는다. */
    PAYMENT_ATTEMPT_NOT_FOUND(404, "결제 시도를 찾을 수 없습니다."),
    DEAD_LETTER_NOT_FOUND(404, "DLQ 메시지를 찾을 수 없습니다."),
    /** 없거나 보관 시간(하루)이 지난 일괄 되돌리기. preorder REPROCESS_BATCH_NOT_FOUND 와 같은 자리다. */
    REDRIVE_BATCH_NOT_FOUND(404, "일괄 되돌리기를 찾을 수 없습니다."),
    /** 결제창을 열 때의 금액이 호출자의 저장 금액과 다르다(D15). */
    PAYMENT_AMOUNT_MISMATCH(409, "결제 금액이 주문 금액과 다릅니다."),
    /** 같은 대상의 동시 승인 시작과 교착했고 다시 해도 풀리지 않았다(D14). 이번 요청은 시작하지 않았다 — 잠시 뒤 다시 부른다. */
    PAYMENT_START_CONFLICT(409, "잠시 후 다시 시도해 주세요."),
    /** 지금 코드로도 읽지 못하는 원문이거나, 되돌리기를 기다리는 상태가 아니다. details.reason 에 까닭을 싣는다. */
    DEAD_LETTER_NOT_REDRIVABLE(409, "되돌릴 수 없는 메시지입니다."),
    /** OPEN 이 아니다. details.reason 에 지금 상태를 싣는다. */
    DEAD_LETTER_NOT_DISCARDABLE(409, "버릴 수 없는 메시지입니다.");

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
