package com.grandis.nova.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * kid → 공개키. 정적 키, JWKS 받기, 모르는 kid 때 갱신 한 번(요청 스레드가 아니라 executor 에서), 10초 안에는 재조회 안 함, 서버가 죽어도 예외 없이 "없음".
 */
@DisplayName("JwtKeyRing — kid 로 공개키 고르기")
class JwtKeyRingTest {

    private static final String JWKS_URI = "http://member.local/.well-known/jwks.json";
    private static final Duration ACCESS = Duration.ofHours(1);
    private static final Duration REFRESH = Duration.ofDays(14);

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-22T00:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static String jwksJson(JwtKeyRing issuerRing) {
        return new ObjectMapper().writeValueAsString(issuerRing.jwks());
    }

    @Test
    @DisplayName("발급 설정: 개인키에서 계산한 공개키가 kid 로 잡히고 JWKS 문서에 kty·kid·use·alg·n·e 가 실린다")
    void issuerRingPublishesDerivedPublicKey() {
        JwtProperties p = TestKeys.issuerProperties("nova-test", ACCESS, REFRESH);
        JwtKeyRing ring = TestKeys.ring(p, Clock.systemUTC());

        assertThat(ring.canSign()).isTrue();
        assertThat(ring.resolve(TestKeys.KID)).contains(TestKeys.publicKey(TestKeys.ISSUER));
        assertThat(ring.resolve("nope")).isEmpty();
        @SuppressWarnings("unchecked")
        var keys = (java.util.List<Map<String, Object>>) ring.jwks().get("keys");
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0)).containsEntry("kty", "RSA").containsEntry("kid", TestKeys.KID).containsEntry("use", "sig").containsEntry("alg", "RS256")
                .containsKeys("n", "e");
        // 우리가 낸 문서를 우리가 다시 읽으면 같은 키
        assertThat(JwtKeyRing.parseJwks(jwksJson(ring))).containsKey(TestKeys.KID);
        assertThat(JwtKeyRing.parseJwks(jwksJson(ring)).get(TestKeys.KID).getModulus()).isEqualTo(TestKeys.publicKey(TestKeys.ISSUER).getModulus());
    }

    @Test
    @DisplayName("검증 전용 설정(public-keys 만): 서명 키가 없어 signingKey() 는 IllegalStateException, 검증은 된다")
    void verifierOnlyRingCannotSign() {
        JwtProperties p = TestKeys.verifierProperties("nova-test", Map.of(TestKeys.KID, TestKeys.publicKey(TestKeys.ISSUER)), ACCESS, REFRESH);
        JwtKeyRing ring = TestKeys.ring(p, Clock.systemUTC());

        assertThat(ring.canSign()).isFalse();
        assertThatThrownBy(ring::signingKey).isInstanceOf(IllegalStateException.class);
        assertThat(ring.resolve(TestKeys.KID)).isPresent();
    }

    @Test
    @DisplayName("jwk-set-uri(동기 executor): 모르는 kid 가 오면 JWKS 를 받아 캐시하고, 10초 안에 또 모르는 kid 가 와도 다시 받지 않는다. 서버 5xx 면 예외 없이 '없음'")
    void jwksFetchCacheAndRateLimit() {
        JwtKeyRing issuerRing = TestKeys.ring(TestKeys.issuerProperties("nova-test", ACCESS, REFRESH), Clock.systemUTC());
        String json = jwksJson(issuerRing);
        MutableClock clock = new MutableClock();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        JwtProperties p = new JwtProperties("nova-test", ACCESS, REFRESH, null, null, null, Map.of(), JWKS_URI, true);   // http 는 명시적으로만
        JwtKeyRing verifier = new JwtKeyRing(p, clock, builder.build(), Runnable::run);   // 동기 executor: 갱신이 그 자리에서 끝난다

        // 1) 첫 조회: 받는다
        server.expect(requestTo(JWKS_URI)).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        assertThat(verifier.resolve(TestKeys.KID)).isPresent();
        server.verify();

        // 2) 캐시된 kid 는 서버를 안 부른다 (기대 요청 0건)
        server.reset();
        assertThat(verifier.resolve(TestKeys.KID)).isPresent();
        server.verify();

        // 3) 모르는 kid 인데 10초 안이면 재조회 안 함
        assertThat(verifier.resolve("rotated-k2")).isEmpty();
        server.verify();

        // 4) 10초 지나면 재조회 — 새 kid 가 있으면 잡힌다
        KeyPair k2 = TestKeys.generate();
        JwtKeyRing rotated = TestKeys.ring(TestKeys.issuerProperties("nova-test", k2, "rotated-k2", ACCESS, REFRESH), Clock.systemUTC());
        clock.now = clock.now.plusSeconds(11);
        server.expect(requestTo(JWKS_URI)).andRespond(withSuccess(jwksJson(rotated), MediaType.APPLICATION_JSON));
        assertThat(verifier.resolve("rotated-k2")).isPresent();
        server.verify();

        // 5) 서버가 죽어도 예외가 아니라 '없음'. 캐시는 유지
        clock.now = clock.now.plusSeconds(11);
        server.reset();
        server.expect(requestTo(JWKS_URI)).andRespond(withServerError());
        assertThat(verifier.resolve("unknown-k3")).isEmpty();
        assertThat(verifier.resolve("rotated-k2")).isPresent();
        server.verify();

        // 6) 실패 뒤에는 10초를 다 쉬지 않는다: 1초 뒤 다음 요청이 다시 받는다. 이번엔 성공하면 그 kid 가 잡힌다
        server.reset();
        KeyPair k3 = TestKeys.generate();
        JwtKeyRing third = TestKeys.ring(TestKeys.issuerProperties("nova-test", k3, "unknown-k3", ACCESS, REFRESH), Clock.systemUTC());
        clock.now = clock.now.plusMillis(500);
        assertThat(verifier.resolve("unknown-k3")).isEmpty();   // 0.5초: 아직
        server.verify();
        clock.now = clock.now.plusMillis(600);                  // 1.1초
        server.expect(requestTo(JWKS_URI)).andRespond(withSuccess(jwksJson(third), MediaType.APPLICATION_JSON));
        assertThat(verifier.resolve("unknown-k3")).isPresent();
        server.verify();
    }

    @Test
    @DisplayName("갱신은 요청 스레드에서 돌지 않는다: 모르는 kid 는 즉시 '없음'(HTTP 0회), 갱신 작업 하나만 executor 에 실리고, 그 작업이 돈 뒤 다음 조회부터 잡힌다")
    void refreshRunsOnExecutorNotOnRequestThread() {
        JwtKeyRing issuerRing = TestKeys.ring(TestKeys.issuerProperties("nova-test", ACCESS, REFRESH), Clock.systemUTC());
        String json = jwksJson(issuerRing);
        MutableClock clock = new MutableClock();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        JwtProperties p = new JwtProperties("nova-test", ACCESS, REFRESH, null, null, null, Map.of(), JWKS_URI, true);
        JwtKeyRing verifier = new JwtKeyRing(p, clock, builder.build(), queued::add);   // 모아만 두는 executor

        // 요청 스레드: HTTP 없이 바로 '없음'. 서버에 기대 요청이 없으므로 여기서 호출이 나가면 MockRestServiceServer 가 바로 실패시킨다
        assertThat(verifier.resolve(TestKeys.KID)).isEmpty();
        assertThat(queued).hasSize(1);
        // 진행 중에 또 모르는 kid 가 와도 작업을 하나 더 걸지 않는다
        assertThat(verifier.resolve("another")).isEmpty();
        assertThat(queued).hasSize(1);
        server.verify();

        // 백그라운드: 그 작업이 돌면 받는다
        server.expect(requestTo(JWKS_URI)).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        queued.get(0).run();
        server.verify();
        assertThat(verifier.resolve(TestKeys.KID)).isPresent();

        // 성공 뒤 10초 안의 모르는 kid 는 작업도 안 건다
        assertThat(verifier.resolve("rotated")).isEmpty();
        assertThat(queued).hasSize(1);
        // 10초 지나면 다시 하나 건다
        clock.now = clock.now.plusSeconds(11);
        assertThat(verifier.resolve("rotated")).isEmpty();
        assertThat(queued).hasSize(2);
    }

    @Test
    @DisplayName("withBackgroundRefresh: jwk-set-uri 가 없으면 스레드를 만들지 않고, 있으면 주기 갱신이 백그라운드에서 돌다가 close() 로 멈춘다")
    void backgroundRefreshLifecycle() throws Exception {
        JwtKeyRing issuerRing = JwtKeyRing.withBackgroundRefresh(TestKeys.issuerProperties("nova-test", ACCESS, REFRESH), Clock.systemUTC(), RestClient.create());
        assertThat(issuerRing.fetchesJwks()).isFalse();
        assertThat(Thread.getAllStackTraces().keySet().stream().map(Thread::getName)).doesNotContain("jwks-refresh");
        issuerRing.close();

        String json = jwksJson(issuerRing);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(JWKS_URI)).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        JwtProperties p = new JwtProperties("nova-test", ACCESS, REFRESH, null, null, null, Map.of(), JWKS_URI, true);
        try (JwtKeyRing verifier = JwtKeyRing.withBackgroundRefresh(p, Clock.systemUTC(), builder.build())) {
            assertThat(verifier.fetchesJwks()).isTrue();
            // 기동 직후 첫 갱신은 백그라운드에서 돈다. 조건 대기(고정 sleep 아님)로 결과를 본다
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (verifier.resolve(TestKeys.KID).isEmpty() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(verifier.resolve(TestKeys.KID)).as("백그라운드 첫 갱신").isPresent();
            server.verify();
            assertThat(Thread.getAllStackTraces().keySet().stream().map(Thread::getName)).contains("jwks-refresh");
        }
        // close() 뒤 스레드가 사라진다 — 종료도 조건 대기
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.getName().equals("jwks-refresh")) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(Thread.getAllStackTraces().keySet().stream().map(Thread::getName)).doesNotContain("jwks-refresh");
    }

    @Test
    @DisplayName("JWKS 의 use≠sig 나 alg≠RS256 인 키는 서명 검증에 쓰지 않는다")
    void jwksIgnoresKeysNotForRs256Signature() {
        JwtKeyRing ring = TestKeys.ring(TestKeys.issuerProperties("nova-test", ACCESS, REFRESH), Clock.systemUTC());
        String json = jwksJson(ring)
                .replace("\"use\":\"sig\"", "\"use\":\"enc\"");
        assertThat(JwtKeyRing.parseJwks(json)).isEmpty();
        String json2 = jwksJson(ring).replace("\"alg\":\"RS256\"", "\"alg\":\"RS512\"");
        assertThat(JwtKeyRing.parseJwks(json2)).isEmpty();
        assertThat(JwtKeyRing.parseJwks(jwksJson(ring))).hasSize(1);
    }

    @Test
    @DisplayName("PEM: PKCS#1(BEGIN RSA PRIVATE KEY)·비 base64·빈 값은 기동에서 걸린다")
    void badPemFailsEarly() {
        // 라벨을 쪼개 쓰는 이유: gitleaks 가 "BEGIN … PRIVATE KEY" 리터럴을 개인키로 오탐한다. 갈래마다 이유를 단언한다(예외 타입만으로 뭉치지 않음)
        String pkcs1 = "-----BEGIN RSA " + "PRIVATE KEY-----\nabc\n-----END RSA " + "PRIVATE KEY-----";
        String garbage = "-----BEGIN " + "PRIVATE KEY-----\n!!!\n-----END " + "PRIVATE KEY-----";
        assertThatThrownBy(() -> PemKeys.parsePrivate(pkcs1)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expected a PEM block labelled");
        assertThatThrownBy(() -> PemKeys.parsePrivate(garbage)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not base64");
        assertThatThrownBy(() -> PemKeys.parsePrivate(" ")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("empty PEM");
        assertThatThrownBy(() -> PemKeys.parsePublic(TestKeys.privatePem(TestKeys.ISSUER))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expected a PEM block labelled");
    }
}
