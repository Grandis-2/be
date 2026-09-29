package com.grandis.nova.catalog.support;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.security.JwtKeyRing;
import com.grandis.nova.common.security.JwtProperties;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.PemKeys;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.client.RestClient;

/**
 * 시험용 액세스 토큰 발급기. member 처럼 RS256 으로 서명하고 앱은 같은 키의 공개키로 검증한다(SecurityTestConfig).
 * 요청에는 실제 헤더(`Authorization: Bearer`)로 실어 인증 필터를 그대로 통과시킨다. preorder 의 같은 이름 도우미를 옮긴 것이다.
 */
public final class AccessTokens {

    public static final String KEY_ID = "catalog-test";
    public static final String ISSUER = "nova-test";

    private static final KeyPair KEYS = generate();
    private static final JwtTokenProvider PROVIDER = provider(KEYS, Clock.systemUTC());

    private AccessTokens() {
    }

    public static RequestPostProcessor customer(long customerId) {
        return withToken(customerToken(customerId));
    }

    public static RequestPostProcessor admin() {
        return withToken(adminToken());
    }

    public static String customerToken(long customerId) {
        return PROVIDER.create(Long.toString(customerId), Role.USER, UUID.randomUUID(), TokenType.ACCESS);
    }

    public static String adminToken() {
        return PROVIDER.create("admin", Role.ADMIN, UUID.randomUUID(), TokenType.ACCESS);
    }

    /** 앱이 모르는 키로 서명한 회원 토큰(서명 불일치). */
    public static String foreignCustomerToken(long customerId) {
        return provider(generate(), Clock.systemUTC()).create(Long.toString(customerId), Role.USER, UUID.randomUUID(), TokenType.ACCESS);
    }

    /** 한 시간 전에 발급돼 이미 만료된(유효 30분) 회원 토큰. */
    public static String expiredCustomerToken(long customerId) {
        Clock anHourAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
        return provider(KEYS, anHourAgo).create(Long.toString(customerId), Role.USER, UUID.randomUUID(), TokenType.ACCESS);
    }

    /** 이미 실린 토큰은 바꾼다 — 같은 요청에 여러 번 붙이면 마지막 것이 쓰인다. */
    public static RequestPostProcessor withToken(String token) {
        return request -> {
            request.removeHeader(BearerTokens.HEADER);
            request.addHeader(BearerTokens.HEADER, BearerTokens.value(token));
            return request;
        };
    }

    static String publicKeyPem() {
        return PemKeys.toPem(KEYS.getPublic());
    }

    private static JwtTokenProvider provider(KeyPair keys, Clock clock) {
        JwtProperties properties = new JwtProperties(ISSUER, Duration.ofMinutes(30), Duration.ofDays(14), null, KEY_ID,
                privateKeyPem(keys), Map.of(), null, null);
        return new JwtTokenProvider(properties, new JwtKeyRing(properties, clock, RestClient.create(), Runnable::run), clock);
    }

    /** PKCS#8 PEM. 라벨을 쪼갠 것은 비밀 탐지기(gitleaks)의 오탐을 피하려는 것이다. */
    private static String privateKeyPem(KeyPair keys) {
        String label = "PRIVATE KEY";
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keys.getPrivate().getEncoded())
                + "\n-----END " + label + "-----\n";
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
