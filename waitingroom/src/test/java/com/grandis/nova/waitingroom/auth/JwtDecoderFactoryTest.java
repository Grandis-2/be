package com.grandis.nova.waitingroom.auth;

import com.grandis.nova.waitingroom.support.TestJwts;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtDecoderFactoryTest {

    static final Instant NOW = Instant.parse("2026-10-01T01:00:00Z");

    private final ReactiveJwtDecoder decoder = JwtDecoderFactory.create(
            properties(Map.of(TestJwts.KID, TestJwts.publicKeyPem())), null, Clock.fixed(NOW, ZoneOffset.UTC));

    private Jwt decode(String token) {
        return decoder.decode(token).block(Duration.ofSeconds(5));
    }

    private static JwtProperties properties(Map<String, String> publicKeys) {
        return new JwtProperties(TestJwts.ISSUER, TestJwts.AUDIENCE, null, null, publicKeys, null);
    }

    @Test
    void member_가_발급한_액세스_토큰을_받고_sub_와_role_을_읽는다() {
        Jwt jwt = decode(TestJwts.user("1024", NOW));

        assertThat(jwt.getSubject()).isEqualTo("1024");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
    }

    @Nested
    class 알고리즘 {

        @Test
        void 공개키를_HMAC_비밀로_쓴_HS256_토큰은_거절한다() throws Exception {
            SignedJWT forged = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(TestJwts.KID).type(new JOSEObjectType("at+jwt")).build(),
                    validClaims());
            forged.sign(new MACSigner(TestJwts.key().toRSAPublicKey().getEncoded()));

            assertThatThrownBy(() -> decode(forged.serialize()))
                    .isInstanceOf(BadJwtException.class).isNotInstanceOf(JwtValidationException.class);
        }

        @Test
        void 서명이_없는_none_토큰은_거절한다() {
            String unsigned = new PlainJWT(validClaims()).serialize();

            assertThatThrownBy(() -> decode(unsigned)).isInstanceOf(BadJwtException.class);
        }

        @Test
        void kid_가_없는_토큰은_거절한다() {
            assertThatThrownBy(() -> decode(TestJwts.builder("1024", NOW).withoutKid().sign()))
                    .isInstanceOf(BadJwtException.class).isNotInstanceOf(JwtValidationException.class);
        }

        @Test
        void JWKS_를_받아_둔_뒤_모르는_kid_는_키_장애가_아니라_토큰_탓이다() {
            JwkSetCache remote = new JwkSetCache(Mono.just(new JWKSet(TestJwts.generate("member-1").toPublicJWK()).toString()),
                    Clock.fixed(NOW, ZoneOffset.UTC));
            remote.refresh().block(Duration.ofSeconds(5));
            JwtProperties jwksOnly = new JwtProperties(TestJwts.ISSUER, TestJwts.AUDIENCE,
                    "https://member.example.com/.well-known/jwks.json", null, null, null);
            ReactiveJwtDecoder withRemote = JwtDecoderFactory.create(jwksOnly, remote,
                    Clock.fixed(NOW, ZoneOffset.UTC));

            assertThatThrownBy(() -> withRemote.decode(TestJwts.builder("1024", NOW).signingKey(TestJwts.generate("forged")).sign())
                    .block(Duration.ofSeconds(5)))
                    .isInstanceOf(BadJwtException.class).isNotInstanceOf(JwtValidationException.class);
        }

        @Test
        void 다른_키로_서명했거나_모르는_kid_면_거절한다() {
            RSAKey other = TestJwts.generate(TestJwts.KID);
            RSAKey unknown = TestJwts.generate("unknown");

            assertThatThrownBy(() -> decode(TestJwts.builder("1024", NOW).signingKey(other).sign()))
                    .isInstanceOf(BadJwtException.class).isNotInstanceOf(JwtValidationException.class);
            assertThatThrownBy(() -> decode(TestJwts.builder("1024", NOW).signingKey(unknown).sign()))
                    .isInstanceOf(BadJwtException.class).isNotInstanceOf(JwtValidationException.class);
        }
    }

    @Nested
    class 클레임 {

        @Test
        void 리프레시_토큰은_거절한다() {
            assertThatThrownBy(() -> decode(TestJwts.builder("1024", NOW).typ("rt+jwt").type("REFRESH").sign()))
                    .isInstanceOf(JwtValidationException.class);
            assertThatThrownBy(() -> decode(TestJwts.builder("1024", NOW).type("REFRESH").sign()))
                    .as("typ 만 맞춘 리프레시").isInstanceOf(JwtValidationException.class);
        }

        @Test
        void typ_이_at_jwt_가_아니면_거절한다() {
            assertThatThrownBy(() -> decode(TestJwts.builder("1024", NOW).typ("JWT").sign()))
                    .isInstanceOf(JwtValidationException.class);
            assertThatThrownBy(() -> decode(TestJwts.builder("1024", NOW).typ(null).sign()))
                    .isInstanceOf(JwtValidationException.class);
        }

        @Test
        void 발급자나_대상이_다르면_거절한다() {
            assertThatThrownBy(() -> decode(TestJwts.builder("1024", NOW).issuer("other").sign()))
                    .isInstanceOf(JwtValidationException.class);
            assertThatThrownBy(() -> decode(TestJwts.builder("1024", NOW).audience("other-api").sign()))
                    .isInstanceOf(JwtValidationException.class);
        }

        @Test
        void 세션_ID_나_토큰_ID_가_없으면_거절한다() {
            for (String claim : new String[]{"sid", "jti"}) {
                assertThatThrownBy(() -> decode(TestJwts.builder("1024", NOW).omit(claim).sign()))
                        .as(claim).isInstanceOf(JwtValidationException.class);
            }
        }

        @Test
        void 문자열이어야_할_클레임이_숫자여도_검증_실패로_끝난다() throws Exception {
            SignedJWT numeric = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(TestJwts.KID).type(new JOSEObjectType("at+jwt")).build(),
                    new JWTClaimsSet.Builder(validClaims()).claim("sid", 42).build());
            numeric.sign(new RSASSASigner(TestJwts.key()));

            assertThatThrownBy(() -> decode(numeric.serialize())).isInstanceOf(JwtValidationException.class);
        }

        @Test
        void 모르는_role_이나_빈_sub_는_거절한다() {
            assertThatThrownBy(() -> decode(TestJwts.builder("1024", NOW).role("ROOT").sign()))
                    .isInstanceOf(JwtValidationException.class);
            assertThatThrownBy(() -> decode(TestJwts.builder(" ", NOW).sign()))
                    .isInstanceOf(JwtValidationException.class);
        }

        @Test
        void 만료는_30초까지_봐주고_그_뒤와_만료_없는_토큰은_거절한다() {
            Instant issuedAt = NOW.minusSeconds(900);
            assertThat(decode(TestJwts.builder("1024", issuedAt).expiresAt(NOW.minusSeconds(29)).sign())).isNotNull();
            assertThatThrownBy(() -> decode(TestJwts.builder("1024", issuedAt).expiresAt(NOW.minusSeconds(31)).sign()))
                    .isInstanceOf(JwtValidationException.class);
            assertThatThrownBy(() -> decode(TestJwts.builder("1024", NOW).expiresAt(null).sign()))
                    .isInstanceOf(JwtValidationException.class);
        }
    }

    @Test
    void 공개키가_2048_비트보다_짧으면_기동에서_막는다() throws Exception {
        RSAKey weak = new RSAKeyGenerator(1024, true).keyID("weak").generate();

        assertThatThrownBy(() -> JwtDecoderFactory.create(properties(Map.of("weak", TestJwts.pem(weak))),
                null, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shorter than 2048");
    }

    private static JWTClaimsSet validClaims() {
        return new JWTClaimsSet.Builder()
                .issuer(TestJwts.ISSUER).audience(TestJwts.AUDIENCE).subject("1024")
                .jwtID(UUID.randomUUID().toString()).claim("sid", UUID.randomUUID().toString())
                .claim("role", "USER").claim("type", "ACCESS")
                .issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(900)))
                .build();
    }
}
