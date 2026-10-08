package com.grandis.nova.payment.vo;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 결제사(토스)에 알리는 주문 번호. 우리 주문 id 가 아니라 CAPTURE 시도마다 새로 발급한다 — 한 주문의 두 번째 시도가
 * 같은 번호를 쓰면 토스가 중복으로 거절한다.
 * 토스 규칙: 6~64자, 영문 대소문자 · 숫자 · '-' · '_'.
 */
public record ProviderOrderId(String value) {

    private static final Pattern TOSS_RULE = Pattern.compile("[A-Za-z0-9_-]{6,64}");

    public ProviderOrderId {
        Objects.requireNonNull(value, "value");
        if (!TOSS_RULE.matcher(value).matches()) {
            throw new IllegalArgumentException("결제사 주문 번호 규칙(6~64자, 영문 · 숫자 · - · _)에 맞지 않는다");
        }
    }

    public static ProviderOrderId issue() {
        return new ProviderOrderId(UUID.randomUUID().toString());
    }
}
