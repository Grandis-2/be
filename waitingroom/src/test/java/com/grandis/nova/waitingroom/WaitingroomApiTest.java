package com.grandis.nova.waitingroom;

import com.grandis.nova.waitingroom.support.TestJwts;
import com.grandis.nova.waitingroom.web.RequestIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 기동 · 인증 · 봉투 · 요청 ID 를 실제 필터 체인으로 확인한다. */
@SpringBootTest(properties = "waitingroom.control.enabled=false")
@Import(WaitingroomApiTest.ProbeController.class)
class WaitingroomApiTest {

    static final Instant NOW = Instant.now();

    @DynamicPropertySource
    static void jwt(DynamicPropertyRegistry registry) {
        registry.add("jwt.issuer", () -> TestJwts.ISSUER);
        registry.add("jwt.public-keys." + TestJwts.KID, TestJwts::publicKeyPem);
        // 아무도 듣지 않는 주소 — 모르는 kid 는 JWKS 를 받으러 갔다가 실패한다
        registry.add("jwt.jwk-set-uri", () -> "http://127.0.0.1:1/.well-known/jwks.json");
        registry.add("jwt.jwk-set-allow-http", () -> "true");
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    HealthIndicator jwksHealthIndicator;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    @Nested
    class 인증 {

        @Test
        void 토큰이_없으면_401_봉투와_Bearer_를_돌려준다() {
            client.get().uri("/api/v1/probe/me").exchange()
                    .expectStatus().isUnauthorized()
                    .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                    .expectBody()
                    .jsonPath("$.success").isEqualTo(false)
                    .jsonPath("$.error.code").isEqualTo("UNAUTHENTICATED");
        }

        @Test
        void 무효한_토큰은_401_invalid_token_이다() {
            client.get().uri("/api/v1/probe/me")
                    .headers(headers -> headers.setBearerAuth(TestJwts.builder("1024", NOW).audience("other").sign()))
                    .exchange()
                    .expectStatus().isUnauthorized()
                    .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
                    .expectBody().jsonPath("$.error.code").isEqualTo("UNAUTHENTICATED");
        }

        @Test
        void 회원은_sub_로_식별한다() {
            client.get().uri("/api/v1/probe/me")
                    .headers(headers -> headers.setBearerAuth(TestJwts.user("1024", NOW)))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.subject").isEqualTo("1024");
        }

        @Test
        void 관리자_경로는_ADMIN_만_들어온다() {
            client.get().uri("/api/v1/admin/probe")
                    .headers(headers -> headers.setBearerAuth(TestJwts.user("1024", NOW)))
                    .exchange()
                    .expectStatus().isForbidden()
                    .expectBody().jsonPath("$.error.code").isEqualTo("FORBIDDEN");
            client.get().uri("/api/v1/admin/probe")
                    .headers(headers -> headers.setBearerAuth(TestJwts.admin(NOW)))
                    .exchange()
                    .expectStatus().isOk();
        }

        @Test
        void JWKS_를_받기_전에는_readiness_가_DOWN_이다() {
            assertThat(jwksHealthIndicator.health().getStatus()).isEqualTo(Status.DOWN);
        }

        @Test
        void 키를_받지_못하면_토큰_탓이_아니므로_503_이다() {
            String unknownKid = TestJwts.builder("1024", NOW).signingKey(TestJwts.generate("rotated")).sign();

            client.get().uri("/api/v1/probe/me")
                    .headers(headers -> headers.setBearerAuth(unknownKid))
                    .exchange()
                    .expectStatus().isEqualTo(503)
                    .expectBody().jsonPath("$.error.code").isEqualTo("DEPENDENCY_UNAVAILABLE");
        }
    }

    @Nested
    class 요청_ID {

        @Test
        void 받은_ID_를_이어_써서_응답_헤더_봉투_로그에_같은_값을_쓴다() {
            client.get().uri("/api/v1/probe/trace")
                    .header(RequestIdFilter.HEADER, "front-abc.1")
                    .headers(headers -> headers.setBearerAuth(TestJwts.user("1024", NOW)))
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals(RequestIdFilter.HEADER, "front-abc.1")
                    .expectBody()
                    .jsonPath("$.header").isEqualTo("front-abc.1")
                    .jsonPath("$.mdc").isEqualTo("front-abc.1");
        }

        @Test
        void 형식이_틀린_ID_는_새로_만들고_같은_응답의_봉투_traceId_와_같다() {
            EntityExchangeResult<byte[]> result = client.get().uri("/api/v1/probe/me")
                    .header(RequestIdFilter.HEADER, "bad id with spaces")
                    .exchange()
                    .expectStatus().isUnauthorized()
                    .expectBody().returnResult();
            String issued = result.getResponseHeaders().getFirst(RequestIdFilter.HEADER);

            assertThat(issued).isNotEqualTo("bad id with spaces").hasSize(36);
            assertThat(new String(result.getResponseBody(), StandardCharsets.UTF_8))
                    .contains("\"traceId\":\"" + issued + "\"");
        }
    }

    @Nested
    class 오류_처리 {

        @Test
        void 예상하지_못한_오류는_원문_없이_500_봉투이고_traceId_를_싣는다() {
            client.get().uri("/api/v1/probe/boom")
                    .header(RequestIdFilter.HEADER, "boom-1")
                    .headers(headers -> headers.setBearerAuth(TestJwts.user("1024", NOW)))
                    .exchange()
                    .expectStatus().isEqualTo(500)
                    .expectBody()
                    .jsonPath("$.error.code").isEqualTo("INTERNAL_ERROR")
                    .jsonPath("$.error.message").isEqualTo("일시적인 오류가 발생했습니다.")
                    .jsonPath("$.traceId").isEqualTo("boom-1");
        }

        @Test
        void 없는_경로는_404_봉투다() {
            client.get().uri("/api/v1/probe/missing")
                    .headers(headers -> headers.setBearerAuth(TestJwts.user("1024", NOW)))
                    .exchange()
                    .expectStatus().isNotFound()
                    .expectBody().jsonPath("$.error.code").isEqualTo("NOT_FOUND");
        }
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/v1/probe/boom")
        Mono<Map<String, String>> boom() {
            return Mono.error(new IllegalStateException("internal detail"));
        }

        @GetMapping("/api/v1/probe/me")
        Map<String, String> me(@AuthenticationPrincipal Jwt jwt) {
            return Map.of("subject", jwt.getSubject());
        }

        @GetMapping("/api/v1/admin/probe")
        Map<String, String> admin() {
            return Map.of("ok", "true");
        }

        /** 다른 스레드로 넘어간 뒤에도 MDC 에 traceId 가 있는지 본다. */
        @GetMapping("/api/v1/probe/trace")
        Mono<Map<String, String>> trace(@RequestHeader(RequestIdFilter.HEADER) String header) {
            return Mono.fromSupplier(() -> Map.of("header", header, "mdc", String.valueOf(MDC.get(RequestIdFilter.TRACE_ID_KEY))))
                    .subscribeOn(Schedulers.boundedElastic());
        }
    }
}
