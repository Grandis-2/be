package com.grandis.nova.member;

import com.grandis.nova.common.security.JwtKeyRing;
import com.grandis.nova.common.security.JwtProperties;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.PemKeys;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.web.client.RestClient;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import org.springframework.test.context.DynamicPropertyRegistry;

/** member 컨텍스트 시험용 RSA 키쌍. 실행 때마다 새로 만든다 — PEM 을 소스에 박지 않는다(gitleaks·진짜 키 유출). */
public final class TestKeys {

    public static final String KID = "test-k1";
    public static final KeyPair ISSUER = generate();

    private TestKeys() {
    }

    static KeyPair generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 개인키 PEM(PKCS#8). 운영 코드에는 개인키 직렬화기가 없어 여기서 만든다. 라벨은 gitleaks 오탐 때문에 쪼갠다. */
    static String privatePem() {
        String label = "PRIVATE KEY";
        return "-----BEGIN " + label + "-----\n" + java.util.Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(ISSUER.getPrivate().getEncoded()) + "\n-----END " + label + "-----\n";
    }

    public static JwtProperties issuerProperties(String issuer, Duration access, Duration refresh) {
        return new JwtProperties(issuer, access, refresh, null, KID, privatePem(), Map.of(), null, null);
    }

    public static JwtTokenProvider issuer(JwtProperties properties, Clock clock) {
        return new JwtTokenProvider(properties, new JwtKeyRing(properties, clock, RestClient.create(), Runnable::run), clock);
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("jwt.key-id", () -> KID);
        registry.add("jwt.private-key", TestKeys::privatePem);
    }
}
