package com.grandis.nova.waitingroom.auth;

import com.grandis.nova.waitingroom.support.TestJwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** jwk-set-uri 없이 정적 공개키만 쓰는 구성(부하 시험 · 로컬). JWKS 캐시를 만들지 않고 jwks 지표는 UP 이다. */
@SpringBootTest(properties = {"jwt.jwk-set-uri=", "waitingroom.control.enabled=false",
        "waitingroom.token.secret=" + TestJwts.TOKEN_SECRET})
class StaticKeysOnlyTest {

    @DynamicPropertySource
    static void jwt(DynamicPropertyRegistry registry) {
        registry.add("jwt.issuer", () -> TestJwts.ISSUER);
        registry.add("jwt.public-keys." + TestJwts.KID, TestJwts::publicKeyPem);
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    ReactiveJwtDecoder decoder;

    @Autowired
    JwksHealthIndicator jwksHealth;

    @Test
    void JWKS_캐시_없이_정적_공개키로_검증하고_jwks_지표는_UP_이다() {
        assertThat(context.getBeanNamesForType(JwkSetCache.class)).isEmpty();
        assertThat(decoder.decode(TestJwts.user("1024", Instant.now())).block(Duration.ofSeconds(5)).getSubject())
                .isEqualTo("1024");
        assertThat(jwksHealth.health().getStatus()).isEqualTo(Status.UP);
    }
}
