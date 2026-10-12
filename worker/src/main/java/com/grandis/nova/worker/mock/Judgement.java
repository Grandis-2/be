package com.grandis.nova.worker.mock;

import java.time.Duration;

/**
 * 응답 하나를 읽은 결과.
 *
 * @param retryNow   같은 차례 안에서 곧바로 다시 보내도 되는 일시 실패다
 * @param retryAfter 외부가 이만큼 뒤에 다시 보내라고 했다. 없으면 null
 */
record Judgement(Verdict verdict, String errorCode, String errorMessage, String externalNumber, boolean retryNow,
                 Duration retryAfter) {

    static Judgement of(Verdict verdict, MockReply reply) {
        return new Judgement(verdict, reply.errorCode(), reply.text("errorMessage"), reply.text("externalNumber"),
                false, null);
    }

    static Judgement success(String externalNumber) {
        return new Judgement(Verdict.SUCCESS, null, null, externalNumber, false, null);
    }

    /** 응답을 받지 못했다. 무엇이 실패했는지를 메시지로 남긴다. */
    static Judgement failed(Verdict verdict, MockReply reply, boolean retryNow) {
        return new Judgement(verdict, null, reply.failure().name(), null, retryNow, null);
    }

    Judgement retryable() {
        return new Judgement(verdict, errorCode, errorMessage, externalNumber, true, retryAfter);
    }

    Judgement after(Duration delay) {
        return new Judgement(verdict, errorCode, errorMessage, externalNumber, retryNow, delay);
    }

    Judgement as(String code) {
        return new Judgement(verdict, code, errorMessage, externalNumber, retryNow, retryAfter);
    }
}
