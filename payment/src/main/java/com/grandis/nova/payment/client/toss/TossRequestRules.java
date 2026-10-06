package com.grandis.nova.payment.client.toss;

import java.util.regex.Pattern;

/** 토스 문서의 요청 값 규칙. 어기면 토스가 INVALID_REQUEST(실패 확정)로 돌려주므로 보내기 전에 막는다. */
final class TossRequestRules {

    static final int PAYMENT_KEY_MAX = 200;
    /** toString 에서 결제 키 자리. 결제 키는 시크릿 키와 함께면 결제를 조작할 수 있는 식별자라 로그에 싣지 않는다(gitleaks 규칙). */
    static final String MASKED = "***";
    /** 로그에 남기는 결제 키 끝 글자 수. 토스 결제 키는 수십 자의 무작위 문자열이라 끝 6자로는 키를 복원할 수 없다 */
    static final int TAIL_LENGTH = 6;
    static final int CANCEL_REASON_MAX = 200;
    /** 영문 대소문자 · 숫자 · -, _ 로 6~64자. */
    static final Pattern ORDER_ID = Pattern.compile("[A-Za-z0-9_-]{6,64}");

    private TossRequestRules() {
    }

    /**
     * 토스 문서는 결제 키의 문자 집합을 정하지 않는다 — 추측한 허용 목록은 정상 키를 거절할 수 있어 두지 않는다. 대신 실제 키(수십 자의
     * 무작위 문자열)일 수 없는 것만 막는다: 경로 세그먼트 "." · ".."(다른 API 경로가 된다), 제어 문자(CR · LF — 로그 끝자리 주입).
     */
    static void requirePaymentKey(String paymentKey) {
        if (paymentKey == null || paymentKey.isBlank() || paymentKey.length() > PAYMENT_KEY_MAX) {
            throw new IllegalArgumentException("paymentKey 는 비어 있지 않은 " + PAYMENT_KEY_MAX + "자 이하여야 한다");
        }
        if (paymentKey.equals(".") || paymentKey.equals("..") || paymentKey.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("paymentKey 에 경로 세그먼트 · 제어 문자가 올 수 없다");
        }
    }

    /** 로그 추적용 결제 키 끝자리("…a1b2c3"). 끝자리만으로 키의 절반 넘게 드러나는 짧은 값은 가린다. */
    static String tail(String paymentKey) {
        if (paymentKey == null || paymentKey.length() < TAIL_LENGTH * 3) {
            return MASKED;
        }
        return "…" + paymentKey.substring(paymentKey.length() - TAIL_LENGTH);
    }

    static void requireOrderId(String orderId) {
        if (orderId == null || !ORDER_ID.matcher(orderId).matches()) {
            throw new IllegalArgumentException("토스 orderId 는 영문 대소문자 · 숫자 · -, _ 로 6~64자여야 한다");
        }
    }

}
