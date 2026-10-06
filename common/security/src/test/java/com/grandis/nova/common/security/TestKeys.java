package com.grandis.nova.common.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.web.client.RestClient;

/**
 * 시험용 RSA 키쌍. **실행 때마다 새로 만든다** — PEM 을 소스에 박으면 gitleaks 가 개인키로 잡고, 진짜 키가 레포에 남는다.
 * JVM 하나에 한 쌍(ISSUER)과 다른 쌍(OTHER)을 둔다. 컨텍스트 시험은 register() 로 발급 서비스 설정을 넣는다.
 */
public final class TestKeys {

    public static final String KID = "test-k1";
    public static final KeyPair ISSUER = generate();
    public static final KeyPair OTHER = generate();

    private TestKeys() {
    }

    public static KeyPair generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 개인키 PEM(PKCS#8). 운영 코드(PemKeys)에는 개인키 직렬화기를 두지 않는다 — 시험만 필요하다. 라벨은 gitleaks 오탐 때문에 쪼갠다. */
    public static String privatePem(KeyPair pair) {
        String label = "PRIVATE KEY";
        return "-----BEGIN " + label + "-----\n" + java.util.Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded()) + "\n-----END " + label + "-----\n";
    }

    public static String publicPem(KeyPair pair) {
        return PemKeys.toPem(pair.getPublic());
    }

    /** 발급 서비스 설정(개인키 + kid). */
    public static JwtProperties issuerProperties(String issuer, KeyPair pair, String kid, Duration access, Duration refresh) {
        return new JwtProperties(issuer, access, refresh, null, kid, privatePem(pair), Map.of(), null, null);
    }

    public static JwtProperties issuerProperties(String issuer, Duration access, Duration refresh) {
        return issuerProperties(issuer, ISSUER, KID, access, refresh);
    }

    /** 검증 전용 설정(정적 공개키만). */
    public static JwtProperties verifierProperties(String issuer, Map<String, RSAPublicKey> publicKeys, Duration access, Duration refresh) {
        Map<String, String> pems = new java.util.LinkedHashMap<>();
        publicKeys.forEach((kid, key) -> pems.put(kid, PemKeys.toPem(key)));
        return new JwtProperties(issuer, access, refresh, null, null, null, pems, null, null);
    }

    public static JwtKeyRing ring(JwtProperties properties, Clock clock) {
        return new JwtKeyRing(properties, clock, RestClient.create(), Runnable::run);
    }

    public static JwtTokenProvider issuer(String issuer, KeyPair pair, String kid, Duration access, Duration refresh, Clock clock) {
        JwtProperties p = issuerProperties(issuer, pair, kid, access, refresh);
        return new JwtTokenProvider(p, ring(p, clock), clock);
    }

    public static RSAPrivateKey privateKey(KeyPair pair) {
        return (RSAPrivateKey) pair.getPrivate();
    }

    public static RSAPublicKey publicKey(KeyPair pair) {
        return (RSAPublicKey) pair.getPublic();
    }

    /** @SpringBootTest 용: jwt.key-id / jwt.private-key 를 발급 키로 넣는다. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("jwt.key-id", () -> KID);
        registry.add("jwt.private-key", () -> privatePem(ISSUER));
    }
}
