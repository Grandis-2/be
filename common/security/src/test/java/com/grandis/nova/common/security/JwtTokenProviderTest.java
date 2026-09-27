package com.grandis.nova.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 확인 항목: 정상 파싱 · 서명 불일치 · issuer 불일치 · 만료 · 클레임 누락 · 타입 불일치, Clock.fixed 로 경계.
 * 시계는 이 테스트가 소유한다. 발급과 파싱이 같은 MutableClock 을 보므로 경계를 1초 단위로 태울 수 있다.
 */
@DisplayName("JwtTokenProvider")
class JwtTokenProviderTest {

    // RS256. ISSUER 키쌍으로 서명하고 같은 kid 를 쓴다. OTHER 는 같은 kid 에 다른 키 — "서명 불일치" 를 만든다.
    private static final String KID = TestKeys.KID;
    private static final String ISSUER = "nova-test";
    private static final Instant FIXED_NOW = Instant.parse("2026-09-19T00:00:00Z");
    private static final Duration ACCESS = Duration.ofHours(1);
    private static final Duration REFRESH = Duration.ofDays(14);

    /** 테스트 안에서만 쓰는 옮길 수 있는 시계. 앱은 Clock.systemUTC() 를 쓴다. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private MutableClock clock;
    private JwtTokenProvider provider;
    private UUID sessionId;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(FIXED_NOW);
        provider = TestKeys.issuer(ISSUER, TestKeys.ISSUER, KID, ACCESS, REFRESH, clock);
        sessionId = UUID.randomUUID();
    }

    @Nested
    @DisplayName("정상 파싱")
    class Parse {

        @Test
        @DisplayName("ACCESS 토큰은 일곱 클레임을 그대로 돌려주고 만료는 발급 시각 + 1h 다")
        void accessTokenRoundTrip() {
            String token = provider.create("101", Role.USER, sessionId, TokenType.ACCESS);

            TokenClaims claims = provider.parse(token);

            assertThat(claims.subject()).isEqualTo("101");
            assertThat(claims.sessionId()).isEqualTo(sessionId);
            assertThat(claims.tokenId()).isNotNull();
            assertThat(claims.role()).isEqualTo(Role.USER);
            assertThat(claims.type()).isEqualTo(TokenType.ACCESS);
            assertThat(claims.issuedAt()).isEqualTo(FIXED_NOW);
            assertThat(claims.expiresAt()).isEqualTo(FIXED_NOW.plus(ACCESS));
        }

        @Test
        @DisplayName("REFRESH 토큰의 만료는 발급 시각 + 14d 다")
        void refreshTokenExpiry() {
            String token = provider.create("101", Role.USER, sessionId, TokenType.REFRESH);

            TokenClaims claims = provider.parse(token);

            assertThat(claims.type()).isEqualTo(TokenType.REFRESH);
            assertThat(claims.expiresAt()).isEqualTo(FIXED_NOW.plus(REFRESH));
        }

        @Test
        @DisplayName("ADMIN 토큰은 subject 가 admin 이고 role 이 ADMIN 이다")
        void adminToken() {
            TokenClaims claims = provider.parse(provider.create("admin", Role.ADMIN, sessionId, TokenType.ACCESS));

            assertThat(claims.subject()).isEqualTo("admin");
            assertThat(claims.role()).isEqualTo(Role.ADMIN);
        }

        @Test
        @DisplayName("같은 세션의 ACCESS 와 REFRESH 는 sid 가 같고 jti 는 다르다")
        void sameSessionDifferentTokenId() {
            TokenClaims access = provider.parse(provider.create("101", Role.USER, sessionId, TokenType.ACCESS));
            TokenClaims refresh = provider.parse(provider.create("101", Role.USER, sessionId, TokenType.REFRESH));

            assertThat(access.sessionId()).isEqualTo(refresh.sessionId());
            assertThat(access.tokenId()).isNotEqualTo(refresh.tokenId());
        }

        @Test
        @DisplayName("만료를 지정해 만들면(회전) 그 만료를 유지하고 jti 만 새로 난다")
        void rotationKeepsExpiry() {
            TokenClaims original = provider.parse(provider.create("101", Role.USER, sessionId, TokenType.REFRESH));
            clock.set(FIXED_NOW.plus(Duration.ofDays(3)));

            TokenClaims rotated = provider.parse(
                    provider.create("101", Role.USER, sessionId, TokenType.REFRESH, original.expiresAt()));

            assertThat(rotated.expiresAt()).isEqualTo(original.expiresAt());
            assertThat(rotated.issuedAt()).isEqualTo(FIXED_NOW.plus(Duration.ofDays(3)));
            assertThat(rotated.tokenId()).isNotEqualTo(original.tokenId());
        }

        @Test
        @DisplayName("지정한 만료가 이미 지났으면 회전 발급을 거부한다")
        void rotationRejectsPastExpiry() {
            assertThatThrownBy(() -> provider.create("101", Role.USER, sessionId, TokenType.REFRESH, FIXED_NOW))
                    .isInstanceOf(InvalidTokenException.class);
        }
    }

    @Nested
    @DisplayName("서명·issuer·형식")
    class SignatureAndIssuer {

        @Test
        @DisplayName("형식이 잘못된 문자열은 실패한다")
        void malformed() {
            assertThatThrownBy(() -> provider.parse("not.a.jwt")).isInstanceOf(InvalidTokenException.class);
        }

        @Test
        @DisplayName("비어 있으면 실패한다")
        void blank() {
            assertThatThrownBy(() -> provider.parse(" ")).isInstanceOf(InvalidTokenException.class);
        }

        @Test
        @DisplayName("다른 secret 으로 서명한 토큰은 실패한다")
        void wrongSignature() {
            JwtTokenProvider other = TestKeys.issuer(ISSUER, TestKeys.OTHER, KID, ACCESS, REFRESH, clock);   // 같은 kid, 다른 개인키
            String token = other.create("101", Role.USER, sessionId, TokenType.ACCESS);

            assertThatThrownBy(() -> provider.parse(token)).isInstanceOf(InvalidTokenException.class);
        }

        @Test
        @DisplayName("issuer 가 다르면 실패한다")
        void wrongIssuer() {
            JwtTokenProvider other = TestKeys.issuer("someone-else", TestKeys.ISSUER, KID, ACCESS, REFRESH, clock);
            String token = other.create("101", Role.USER, sessionId, TokenType.ACCESS);

            assertThatThrownBy(() -> provider.parse(token)).isInstanceOf(InvalidTokenException.class);
        }

        @Test
        @DisplayName("서명 없는 토큰(alg=none)은 실패한다 — parseSignedClaims 가 거절하고 우리 예외로 뭉친다")
        void unsignedTokenRejected() {
            String unsigned = Jwts.builder()
                    .id(UUID.randomUUID().toString())
                    .issuer(ISSUER)
                    .subject("101")
                    .claim(JwtTokenProvider.CLAIM_SESSION_ID, sessionId.toString())
                    .claim(JwtTokenProvider.CLAIM_ROLE, Role.USER.name())
                    .claim(JwtTokenProvider.CLAIM_TYPE, TokenType.ACCESS.name())
                    .issuedAt(Date.from(FIXED_NOW))
                    .expiration(Date.from(FIXED_NOW.plus(ACCESS)))
                    .compact();

            assertThat(unsigned).endsWith(".");
            assertThatThrownBy(() -> provider.parse(unsigned)).isInstanceOf(InvalidTokenException.class);
        }

        @Test
        @DisplayName("헤더에 alg=RS256·kid 가 실리고, 공개키로 검증된다 (개인키는 발급 쪽에만)")
        void rs256HeaderAndPublicKeyVerification() {
            String token = provider.create("101", Role.USER, sessionId, TokenType.ACCESS);
            var jws = Jwts.parser()
                    .verifyWith(TestKeys.publicKey(TestKeys.ISSUER))
                    .clock(() -> Date.from(clock.instant()))   // 시계를 안 넣으면 시스템 시각으로 만료 판정이 나서 못 잰다
                    .build().parseSignedClaims(token);

            assertThat(jws.getHeader().getAlgorithm()).isEqualTo("RS256");
            assertThat(jws.getHeader().getKeyId()).isEqualTo(KID);
            assertThat(provider.parse(token).subject()).isEqualTo("101");
        }

        @Test
        @DisplayName("kid 가 없거나 모르는 kid 면 실패한다 — 키를 못 고르니 서명을 볼 것도 없다")
        void missingOrUnknownKidRejected() {
            io.jsonwebtoken.JwtBuilder base = Jwts.builder()
                    .id(UUID.randomUUID().toString()).issuer(ISSUER).audience().add(JwtProperties.DEFAULT_AUDIENCE).and().subject("101")
                    .claim(JwtTokenProvider.CLAIM_SESSION_ID, sessionId.toString()).claim(JwtTokenProvider.CLAIM_ROLE, "USER").claim(JwtTokenProvider.CLAIM_TYPE, "ACCESS")
                    .issuedAt(Date.from(FIXED_NOW)).expiration(Date.from(FIXED_NOW.plus(ACCESS)));
            String noKid = base.header().type("at+jwt").and().signWith(TestKeys.privateKey(TestKeys.ISSUER), Jwts.SIG.RS256).compact();
            String unknownKid = base.header().type("at+jwt").keyId("someone-elses-key").and().signWith(TestKeys.privateKey(TestKeys.ISSUER), Jwts.SIG.RS256).compact();

            assertThatThrownBy(() -> provider.parse(noKid)).isInstanceOf(InvalidTokenException.class)
                    .extracting(e -> ((InvalidTokenException) e).reason()).asString().contains("missing kid");
            assertThatThrownBy(() -> provider.parse(unknownKid)).isInstanceOf(InvalidTokenException.class)
                    .extracting(e -> ((InvalidTokenException) e).reason()).asString().contains("unknown kid");
        }

        @Test
        @DisplayName("같은 개인키로 RS512 서명한 토큰은 **우리** alg 확인이 거절한다 — jjwt 는 RSA 공개키로 RS512 도 검증해 통과시키므로(실측) 이 확인이 없으면 alg 가 자유다")
        void rs512WithOurKeyRejectedByAlgCheck() {
            String token = Jwts.builder()
                    .header().type("at+jwt").keyId(KID).and()
                    .id(UUID.randomUUID().toString()).issuer(ISSUER).audience().add(JwtProperties.DEFAULT_AUDIENCE).and().subject("101")
                    .claim(JwtTokenProvider.CLAIM_SESSION_ID, sessionId.toString()).claim(JwtTokenProvider.CLAIM_ROLE, "USER").claim(JwtTokenProvider.CLAIM_TYPE, "ACCESS")
                    .issuedAt(Date.from(FIXED_NOW)).expiration(Date.from(FIXED_NOW.plus(ACCESS)))
                    .signWith(TestKeys.privateKey(TestKeys.ISSUER), Jwts.SIG.RS512).compact();
            // 대조군: jjwt 만으로는 통과한다 — 그래서 우리 확인이 필요하다
            assertThat(Jwts.parser().verifyWith(TestKeys.publicKey(TestKeys.ISSUER)).clock(() -> Date.from(clock.instant())).build()
                    .parseSignedClaims(token).getHeader().getAlgorithm()).isEqualTo("RS512");

            assertThatThrownBy(() -> provider.parse(token)).isInstanceOf(InvalidTokenException.class)
                    .extracting(e -> ((InvalidTokenException) e).reason()).asString().contains("alg is not RS256");
        }

        @Test
        @DisplayName("같은 kid 를 달고 HMAC(HS256)으로 서명한 토큰은 실패한다 — 이건 jjwt 가 서명 단계에서 거절한다(RSA 공개키로 HMAC 을 검증 못 함). 우리 alg 확인은 위 RS512 시험이 태운다")
        void hmacSignedTokenWithOurKidRejected() {
            javax.crypto.SecretKey hmac = io.jsonwebtoken.security.Keys.hmacShaKeyFor(TestKeys.publicKey(TestKeys.ISSUER).getEncoded());
            String token = Jwts.builder()
                    .header().type("at+jwt").keyId(KID).and()
                    .id(UUID.randomUUID().toString()).issuer(ISSUER).audience().add(JwtProperties.DEFAULT_AUDIENCE).and().subject("101")
                    .claim(JwtTokenProvider.CLAIM_SESSION_ID, sessionId.toString()).claim(JwtTokenProvider.CLAIM_ROLE, "USER").claim(JwtTokenProvider.CLAIM_TYPE, "ACCESS")
                    .issuedAt(Date.from(FIXED_NOW)).expiration(Date.from(FIXED_NOW.plus(ACCESS)))
                    .signWith(hmac).compact();

            assertThatThrownBy(() -> provider.parse(token)).isInstanceOf(InvalidTokenException.class);
        }

        @Test
        @DisplayName("payload 를 바꾸면 서명이 깨져 실패한다")
        void tamperedPayload() {
            String token = provider.create("101", Role.USER, sessionId, TokenType.ACCESS);
            String[] parts = token.split("\\.");
            String tamperedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                            .replace("\"role\":\"USER\"", "\"role\":\"ADMIN\"")
                            .getBytes(StandardCharsets.UTF_8));
            String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

            assertThatThrownBy(() -> provider.parse(tampered)).isInstanceOf(InvalidTokenException.class);
        }
    }

    @Nested
    @DisplayName("만료 경계 (Clock.fixed)")
    class Expiry {

        @Test
        @DisplayName("만료 1초 전은 유효하다")
        void oneSecondBeforeExpiryIsValid() {
            String token = provider.create("101", Role.USER, sessionId, TokenType.ACCESS);
            clock.set(FIXED_NOW.plus(ACCESS).minusSeconds(1));

            assertThat(provider.parse(token).subject()).isEqualTo("101");
        }

        @Test
        @DisplayName("만료와 같은 순간은 만료다 (exp 는 '이때부터 무효')")
        void atExpiryIsExpired() {
            String token = provider.create("101", Role.USER, sessionId, TokenType.ACCESS);
            clock.set(FIXED_NOW.plus(ACCESS));

            assertThatThrownBy(() -> provider.parse(token)).isInstanceOf(InvalidTokenException.class);
        }

        @Test
        @DisplayName("만료 1초 뒤는 만료다")
        void afterExpiryIsExpired() {
            String token = provider.create("101", Role.USER, sessionId, TokenType.ACCESS);
            clock.set(FIXED_NOW.plus(ACCESS).plusSeconds(1));

            assertThatThrownBy(() -> provider.parse(token)).isInstanceOf(InvalidTokenException.class);
        }
    }

    @Nested
    @DisplayName("필수 클레임 누락·값 오류 (같은 키·같은 issuer 로 직접 만든 토큰)")
    class RequiredClaims {

        private final java.security.interfaces.RSAPrivateKey key = TestKeys.privateKey(TestKeys.ISSUER);

        /** 일곱 클레임을 전부 채운 뒤 하나를 빼거나 바꾼다. 서명·issuer·aud·typ 은 정상이라 실패 원인이 그 클레임 하나로 좁혀진다. */
        private String tokenWithout(Consumer<JwtBuilder> mutate) {
            JwtBuilder builder = Jwts.builder()
                    .header().type("at+jwt").keyId(KID).and()
                    .id(UUID.randomUUID().toString())
                    .issuer(ISSUER)
                    .audience().add(JwtProperties.DEFAULT_AUDIENCE).and()
                    .subject("101")
                    .claim(JwtTokenProvider.CLAIM_SESSION_ID, sessionId.toString())
                    .claim(JwtTokenProvider.CLAIM_ROLE, Role.USER.name())
                    .claim(JwtTokenProvider.CLAIM_TYPE, TokenType.ACCESS.name())
                    .issuedAt(Date.from(FIXED_NOW))
                    .expiration(Date.from(FIXED_NOW.plus(ACCESS)));
            mutate.accept(builder);
            return builder.signWith(key, Jwts.SIG.RS256).compact();
        }

        private void assertRejected(String token, String expectedReasonPart) {
            assertThatThrownBy(() -> provider.parse(token))
                    .isInstanceOf(InvalidTokenException.class)
                    .extracting(e -> ((InvalidTokenException) e).reason())
                    .asString()
                    .contains(expectedReasonPart);
        }

        @Test
        @DisplayName("기준: 일곱 클레임이 다 있으면 통과한다")
        void baselinePasses() {
            assertThat(provider.parse(tokenWithout(b -> { })).subject()).isEqualTo("101");
        }

        @Test
        @DisplayName("sub 없음")
        void missingSubject() {
            assertRejected(tokenWithout(b -> b.subject(null)), "sub");
        }

        @Test
        @DisplayName("sid 없음")
        void missingSessionId() {
            assertRejected(tokenWithout(b -> b.claim(JwtTokenProvider.CLAIM_SESSION_ID, null)), "sid");
        }

        @Test
        @DisplayName("jti 없음")
        void missingTokenId() {
            assertRejected(tokenWithout(b -> b.id(null)), "jti");
        }

        @Test
        @DisplayName("role 없음")
        void missingRole() {
            assertRejected(tokenWithout(b -> b.claim(JwtTokenProvider.CLAIM_ROLE, null)), "role");
        }

        @Test
        @DisplayName("type 없음")
        void missingType() {
            assertRejected(tokenWithout(b -> b.claim(JwtTokenProvider.CLAIM_TYPE, null)), "type");
        }

        @Test
        @DisplayName("exp 없음 — jjwt 는 exp 없는 토큰을 통과시키므로 이 클래스가 막아야 한다")
        void missingExpiration() {
            assertRejected(tokenWithout(b -> b.expiration(null)), "exp");
        }

        @Test
        @DisplayName("iat 없음 — nbf 비교 기준이라 필수다")
        void missingIssuedAt() {
            assertRejected(tokenWithout(b -> b.issuedAt(null)), "iat");
        }

        @Test
        @DisplayName("sid 가 UUID 형식이 아니면 실패한다")
        void malformedSessionId() {
            assertRejected(tokenWithout(b -> b.claim(JwtTokenProvider.CLAIM_SESSION_ID, "not-a-uuid")), "sid");
        }

        @Test
        @DisplayName("sid 가 문자열이 아니면(숫자) jjwt 의 타입 변환 예외도 우리 예외로 뭉친다")
        void sessionIdNotAString() {
            assertRejected(tokenWithout(b -> b.claim(JwtTokenProvider.CLAIM_SESSION_ID, 12345)), "sid");
        }

        @Test
        @DisplayName("role 값이 USER/ADMIN 밖이면 실패한다")
        void unknownRole() {
            assertRejected(tokenWithout(b -> b.claim(JwtTokenProvider.CLAIM_ROLE, "SELLER")), "role");
        }

        @Test
        @DisplayName("type 값이 ACCESS/REFRESH 밖이면 실패한다")
        void unknownType() {
            assertRejected(tokenWithout(b -> b.claim(JwtTokenProvider.CLAIM_TYPE, "ID")), "type");
        }
    }
}
