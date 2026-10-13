package com.grandis.nova.waitingroom.auth;

import com.grandis.nova.waitingroom.support.TestJwts;
import com.nimbusds.jose.jwk.JWKSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** member 가 늦게 떠도 요청 없이 JWKS 를 다시 받아 jwks 지표가 UP 이 된다 — 준비 전에는 ALB 가 요청을 보내지 않는다. */
@SpringBootTest(properties = {"waitingroom.control.enabled=false", "waitingroom.token.secret=" + TestJwts.TOKEN_SECRET})
class JwksLateMemberTest {

    static final int FAILING_CALLS = 2;
    static final AtomicInteger CALLS = new AtomicInteger();

    static final DisposableServer MEMBER = HttpServer.create()
            .port(0)
            .route(routes -> routes.get("/.well-known/jwks.json", (request, response) ->
                    CALLS.incrementAndGet() <= FAILING_CALLS
                            ? response.status(503).send()
                            : response.header("Content-Type", "application/json")
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

    @Test
    void 처음_몇_번을_못_받아도_요청_없이_다시_받아_jwks_지표가_UP_이_된다() {
        StepVerifier.create(Flux.interval(Duration.ofMillis(20)).map(tick -> jwksHealth.health().getStatus())
                        .filter(Status.UP::equals).next())
                .expectNext(Status.UP)
                .expectComplete()
                .verify(Duration.ofSeconds(20));

        assertThat(CALLS).hasValueGreaterThan(FAILING_CALLS);
    }
}
