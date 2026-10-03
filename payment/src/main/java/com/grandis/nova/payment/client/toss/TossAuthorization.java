package com.grandis.nova.payment.client.toss;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

/**
 * 토스 인증 헤더: {@code Authorization: Basic base64(secretKey + ":")} — 시크릿 키가 사용자 ID, 비밀번호는 비운다(콜론 필수).
 * 우리 API 의 사용자 인증(Authorization: Bearer, common:security)과는 별개다. 운영(HttpClientConfig)과 테스트가 이것 하나를 쓴다.
 */
public final class TossAuthorization {

    /** spring.http.serviceclient 그룹 이름. */
    public static final String GROUP = "toss";

    private TossAuthorization() {
    }

    public static void apply(RestClient.Builder builder, TossProperties properties) {
        String encoded = HttpHeaders.encodeBasicAuth(properties.secretKey(), "", StandardCharsets.UTF_8);
        builder.defaultHeaders(headers -> headers.setBasicAuth(encoded));
    }
}
