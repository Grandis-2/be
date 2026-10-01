package com.grandis.nova.waitingroom.auth;

import com.grandis.nova.waitingroom.support.TestJwts;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 요청은 받기를 기다리지 않고, 인증 없는 요청이 member 를 두드리지 못하는가. */
class JwkSetCacheTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-01T00:00:00Z"));

    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    private final AtomicInteger fetches = new AtomicInteger();
    private final AtomicReference<Mono<String>> response = new AtomicReference<>();
    private final JwkSetCache cache = new JwkSetCache(Mono.defer(() -> {
        fetches.incrementAndGet();
        return response.get();
    }), clock);

    private static String keySet(String... kids) {
        List<JWK> keys = new ArrayList<>();
        for (String kid : kids) {
            keys.add(TestJwts.generate(kid).toPublicJWK());
        }
        return new JWKSet(keys).toString();
    }

    private static SignedJWT token(String kid) throws Exception {
        Base64URL signature = Base64URL.encode("x");
        return new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build().toBase64URL(),
                new Payload("{}").toBase64URL(), signature);
    }

    private List<String> kids(String kid) throws Exception {
        return cache.apply(token(kid)).map(JWK::getKeyID).collectList().block(Duration.ofSeconds(5));
    }

    /** 기동 때 받기. */
    private void loaded(String... kids) {
        response.set(Mono.just(keySet(kids)));
        cache.refresh().block(Duration.ofSeconds(5));
    }

    private void elapse(Duration duration) {
        now.set(now.get().plus(duration));
    }

    @Nested
    class 요청_경로 {

        @Test
        void 받아_둔_키로만_답하고_요청마다_받으러_가지_않는다() throws Exception {
            loaded("k1");

            assertThat(kids("k1")).containsExactly("k1");
            assertThat(kids("k1")).containsExactly("k1");
            assertThat(fetches).hasValue(1);
            assertThat(cache.loadedOnce()).isTrue();
        }

        @Test
        void 아직_못_받았으면_오류이고_뒤에서_받으러_간다() {
            response.set(Mono.just(keySet("k1")));

            assertThatThrownBy(() -> kids("k1")).hasMessageContaining("JWKS is not available");
            assertThat(fetches).hasValue(1);
            assertThat(cache.loadedOnce()).as("뒤에서 받은 것이 다음 요청부터 쓰인다").isTrue();
        }

        @Test
        void 모르는_kid_는_기다리지_않고_가진_키로_답하며_간격_안에서는_한_번만_다시_받는다() throws Exception {
            loaded("k1");
            response.set(Mono.never());
            elapse(JwkSetCache.MIN_INTERVAL);

            for (int i = 0; i < 100; i++) {
                assertThat(kids("forged-" + i)).containsExactly("k1");
            }
            assertThat(fetches).as("기동 한 번 + 간격이 지나 한 번").hasValue(2);
        }

        @Test
        void 모르는_kid_가_다시_받게_하는_것은_마지막_시도에서_10초가_지나야_한다() throws Exception {
            loaded("k1");
            kids("unknown-1");
            assertThat(fetches).as("기동 한 번 + 모르는 kid 로 한 번").hasValue(2);

            elapse(JwkSetCache.MIN_INTERVAL.minusSeconds(1));
            kids("unknown-2");
            assertThat(fetches).hasValue(2);

            elapse(Duration.ofSeconds(1));
            kids("unknown-3");
            assertThat(fetches).hasValue(3);
        }

        @Test
        void member_가_키를_돌리면_뒤에서_받은_뒤_새_kid_가_잡힌다() throws Exception {
            loaded("k1");
            response.set(Mono.just(keySet("k1", "k2")));
            elapse(JwkSetCache.MIN_INTERVAL);

            kids("k2");
            assertThat(kids("k2")).contains("k2");
        }
    }

    @Nested
    class 장애 {

        @Test
        void 다시_받다_실패하면_가진_것으로_버티고_회복하면_새것으로_바꾼다() throws Exception {
            loaded("k1");
            response.set(Mono.error(new IllegalStateException("member 장애")));
            elapse(JwkSetCache.REFRESH_INTERVAL);
            assertThatThrownBy(() -> cache.refresh().block()).hasMessageContaining("member 장애");
            assertThat(kids("k1")).containsExactly("k1");

            response.set(Mono.just(keySet("k2")));
            cache.refresh().block(Duration.ofSeconds(5));
            assertThat(kids("k2")).containsExactly("k2");
        }

        @Test
        void 버티는_것은_마지막으로_받은_뒤_한_시간까지다() throws Exception {
            loaded("k1");
            response.set(Mono.error(new IllegalStateException("member 장애")));

            elapse(JwkSetCache.STALE_LIMIT.minusSeconds(1));
            assertThat(kids("k1")).containsExactly("k1");
            elapse(Duration.ofSeconds(1));
            assertThatThrownBy(() -> kids("k1")).hasMessageContaining("JWKS is not available");
            assertThat(cache.loadedOnce()).as("이미 뜬 태스크는 readiness 를 내리지 않는다 — 요청이 503 으로 답한다").isTrue();
        }

        @Test
        void 본문이_키_집합이_아니면_받기는_실패로_끝난다() {
            response.set(Mono.just("not json"));

            assertThatThrownBy(() -> cache.refresh().block()).hasMessageContaining("JWKS 를 읽지 못했다");
            assertThat(cache.loadedOnce()).isFalse();
        }

        @Test
        void 쓸_키가_없는_집합은_실패로_보고_갖고_있던_키를_지킨다() throws Exception {
            loaded("k1");
            response.set(Mono.just("{\"keys\":[]}"));

            assertThatThrownBy(() -> cache.refresh().block()).hasMessageContaining("쓸 수 있는 키가 없다");
            assertThat(kids("k1")).containsExactly("k1");
        }

        @Test
        void 응답이_안_오면_시한에_끊는다() {
            response.set(Mono.never());

            StepVerifier.withVirtualTime(cache::refresh)
                    .expectSubscription()
                    .thenAwait(JwkSetCache.TIMEOUT)
                    .expectError(TimeoutException.class)
                    .verify(Duration.ofSeconds(5));
        }
    }

    @Test
    void RSA_키가_2048_비트보다_짧으면_받은_집합에서_버린다() throws Exception {
        JWK weak = new RSAKeyGenerator(1024, true).keyID("weak").generate().toPublicJWK();
        JWK strong = TestJwts.generate("k1").toPublicJWK();
        response.set(Mono.just(new JWKSet(List.of(weak, strong)).toString()));
        cache.refresh().block(Duration.ofSeconds(5));

        assertThat(kids("k1")).containsExactly("k1");
    }

    @Test
    void 기동하면_바로_받고_멈추면_주기_갱신을_끝낸다() {
        response.set(Mono.just(keySet("k1")));

        cache.start();
        try {
            assertThat(cache.isRunning()).isTrue();
            StepVerifier.create(Flux.interval(Duration.ofMillis(10)).map(tick -> cache.loadedOnce())
                            .filter(Boolean::booleanValue).next())
                    .expectNext(true)
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));
        } finally {
            cache.stop();
        }
        assertThat(cache.isRunning()).isFalse();
    }
}
