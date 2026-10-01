package com.grandis.nova.waitingroom.auth;

import com.grandis.nova.waitingroom.support.TestJwts;
import com.nimbusds.jose.jwk.JWKSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 앱이 뜨면 JWKS 를 뒤에서 받아 readiness 가 UP 이 되고, 정적 공개키 없이 JWKS 키로 검증한다. */
@SpringBootTest
class JwksLoadingTest {

    static final DisposableServer MEMBER = HttpServer.create()
            .port(0)
            .route(routes -> routes.get("/.well-known/jwks.json", (request, response) -> response
                    .header("Content-Type", "application/json")
                    .sendString(Mono.just(new JWKSet(TestJwts.key().toPublicJWK()).toString()))))
            .bindNow();

    @DynamicPropertySource
    static void jwt(DynamicPropertyRegistry registry) {
        registry.add("jwt.issuer", () -> TestJwts.ISSUER);
        registry.add("jwt.jwk-set-uri", () -> "http://localhost:" + MEMBER.port() + "/.well-known/jwks.json");
        registry.add("jwt.jwk-set-allow-http", () -> "true");
    }

    @AfterAll
    static void stopMember() {
        MEMBER.disposeNow();
    }

    @Autowired
    JwksHealthIndicator jwksHealth;

    @Autowired
    ReactiveJwtDecoder decoder;

    @Test
    void 기동하면_JWKS_를_받아_readiness_가_UP_이_되고_그_키로_검증한다() {
        StepVerifier.create(Flux.interval(Duration.ofMillis(20)).map(tick -> jwksHealth.health().getStatus())
                        .filter(Status.UP::equals).next())
                .expectNext(Status.UP)
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        assertThat(decoder.decode(TestJwts.user("1024", Instant.now())).block(Duration.ofSeconds(5)).getSubject())
                .isEqualTo("1024");
    }
}
