package com.grandis.nova.payment.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * 테스트 컨텍스트의 JWT 서명 키. **실행마다 새로 만든다** — PEM 을 소스에 박으면 gitleaks 가 개인키로 잡고, 진짜 키가 저장소에 남는다
 * (common:security · member 의 TestKeys 와 같은 방식. 테스트 소스는 모듈을 넘어 공유되지 않아 따로 둔다).
 *
 * 운영 payment 는 검증만 한다(jwk-set-uri). 테스트는 개인키를 줘 발급 모드로 띄운다 — JwtTokenProvider.create 로 진짜 토큰을
 * 만들어 필터 · 폐기 조회 정책을 그대로 태우기 위해서다. 토큰 파싱 경로는 두 모드가 같다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestJwt {

    public static final String KID = "payment-test-k1";

    private static final KeyPair PAIR = generate();

    @Bean
    DynamicPropertyRegistrar jwtSigningProperties() {
        return registry -> {
            registry.add("jwt.key-id", () -> KID);
            registry.add("jwt.private-key", TestJwt::privatePem);
        };
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** PKCS#8 PEM. 라벨은 gitleaks 오탐 때문에 쪼갠다. */
    private static String privatePem() {
        String label = "PRIVATE KEY";
        return "-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(PAIR.getPrivate().getEncoded())
                + "\n-----END " + label + "-----\n";
    }
}
