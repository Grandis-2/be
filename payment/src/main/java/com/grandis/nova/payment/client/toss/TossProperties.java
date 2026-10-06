package com.grandis.nova.payment.client.toss;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 토스 가맹점 자격 증명. 설정(환경 변수 · 비밀 저장소)으로만 받는다 — 저장소 · 로그 · 예외 메시지 · toString 에 싣지 않는다.
 * 주소 · 타임아웃은 여기가 아니라 spring.http.serviceclient.toss 다(D9).
 *
 * @param secretKey 시크릿 키(test_sk_… · live_sk_…). 없으면 기동하지 않는다
 */
@ConfigurationProperties("nova.payment.toss")
public record TossProperties(String secretKey) {

    public TossProperties {
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("nova.payment.toss.secret-key 가 필요하다");
        }
    }

    @Override
    public String toString() {
        return "TossProperties[secretKey=" + TossRequestRules.MASKED + "]";
    }
}
