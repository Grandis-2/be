package com.grandis.nova.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Jwts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RFC 8725 §3.9(aud 검증)·§3.11(typ 명시)·§3.12(종류별 배타 규칙). 같은 키로 서명했어도 aud 가 없거나 다르면, typ 이 없거나 종류와 어긋나면 거절.
 */
@DisplayName("JwtTokenProvider — aud · typ")
class JwtAudienceAndTypTest {

    private static final String KID = TestKeys.KID;
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final JwtProperties properties = TestKeys.issuerProperties("nova-test", TestKeys.ISSUER, KID, Duration.ofHours(1), Duration.ofDays(14));
    private final JwtTokenProvider provider = new JwtTokenProvider(properties, TestKeys.ring(properties, clock), clock);
    private final java.security.interfaces.RSAPrivateKey key = TestKeys.privateKey(TestKeys.ISSUER);
    private final java.security.interfaces.RSAPublicKey pub = TestKeys.publicKey(TestKeys.ISSUER);

    private io.jsonwebtoken.JwtBuilder validAccess() {
        return Jwts.builder()
                .header().type("at+jwt").keyId(KID).and()
                .id(UUID.randomUUID().toString()).issuer("nova-test").audience().add("nova-api").and()
                .subject("101").claim("sid", UUID.randomUUID().toString()).claim("role", "USER").claim("type", "ACCESS")
                .issuedAt(Date.from(NOW)).expiration(Date.from(NOW.plusSeconds(3600))).signWith(key, Jwts.SIG.RS256);
    }

    @Test
    @DisplayName("발급한 토큰에는 aud=nova-api 와 typ=at+jwt / rt+jwt 가 실려 있고 그대로 파싱된다")
    void issuedTokensCarryAudienceAndTyp() {
        String access = provider.create("101", Role.USER, UUID.randomUUID(), TokenType.ACCESS);
        String refresh = provider.create("101", Role.USER, UUID.randomUUID(), TokenType.REFRESH);
        var parsedAccess = Jwts.parser().verifyWith(pub).clock(() -> Date.from(NOW)).build().parseSignedClaims(access);
        var parsedRefresh = Jwts.parser().verifyWith(pub).clock(() -> Date.from(NOW)).build().parseSignedClaims(refresh);

        assertThat(parsedAccess.getHeader().getType()).isEqualTo("at+jwt");
        assertThat(parsedRefresh.getHeader().getType()).isEqualTo("rt+jwt");
        assertThat(parsedAccess.getPayload().getAudience()).containsExactly("nova-api");
        assertThat(provider.parse(access).type()).isEqualTo(TokenType.ACCESS);
        assertThat(provider.parse(refresh).type()).isEqualTo(TokenType.REFRESH);
    }

    @Test
    @DisplayName("aud 가 없으면 거절")
    void missingAudienceIsRejected() {
        String token = validAccess().audience().add("nova-api").and().compact();
        assertThat(provider.parse(token)).isNotNull();   // 대조군: 있으면 통과
        String without = Jwts.builder()
                .header().type("at+jwt").keyId(KID).and()
                .id(UUID.randomUUID().toString()).issuer("nova-test")
                .subject("101").claim("sid", UUID.randomUUID().toString()).claim("role", "USER").claim("type", "ACCESS")
                .issuedAt(Date.from(NOW)).expiration(Date.from(NOW.plusSeconds(3600))).signWith(key, Jwts.SIG.RS256).compact();
        assertThatThrownBy(() -> provider.parse(without)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("aud 가 다르면(같은 키로 서명한 다른 용도의 토큰) 거절")
    void otherAudienceIsRejected() {
        String token = Jwts.builder()
                .header().type("at+jwt").keyId(KID).and()
                .id(UUID.randomUUID().toString()).issuer("nova-test").audience().add("nova-batch").and()
                .subject("101").claim("sid", UUID.randomUUID().toString()).claim("role", "USER").claim("type", "ACCESS")
                .issuedAt(Date.from(NOW)).expiration(Date.from(NOW.plusSeconds(3600))).signWith(key, Jwts.SIG.RS256).compact();
        assertThatThrownBy(() -> provider.parse(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("typ 이 없거나 type 클레임과 어긋나면 거절 — 리프레시 클레임에 at+jwt 헤더, 액세스 클레임에 typ 없음")
    void typMismatchIsRejected() {
        String refreshClaimsWithAccessTyp = validAccess().claim("type", "REFRESH")
                .expiration(Date.from(NOW.plus(Duration.ofDays(14)))).compact();
        String noTyp = Jwts.builder()
                .header().keyId(KID).and()
                .id(UUID.randomUUID().toString()).issuer("nova-test").audience().add("nova-api").and()
                .subject("101").claim("sid", UUID.randomUUID().toString()).claim("role", "USER").claim("type", "ACCESS")
                .issuedAt(Date.from(NOW)).expiration(Date.from(NOW.plusSeconds(3600))).signWith(key, Jwts.SIG.RS256).compact();

        assertThatThrownBy(() -> provider.parse(refreshClaimsWithAccessTyp)).isInstanceOf(InvalidTokenException.class)
                .extracting(e -> ((InvalidTokenException) e).reason()).asString().contains("typ");
        assertThatThrownBy(() -> provider.parse(noTyp)).isInstanceOf(InvalidTokenException.class)
                .extracting(e -> ((InvalidTokenException) e).reason()).asString().contains("typ");
    }

    @Test
    @DisplayName("audience 를 안 주면 기본 nova-api, 주면 그 값. toString 에 실린다")
    void audienceDefault() {
        assertThat(TestKeys.issuerProperties("nova", Duration.ofHours(1), Duration.ofDays(14)).audience()).isEqualTo("nova-api");
        assertThat(new JwtProperties("nova", Duration.ofHours(1), Duration.ofDays(14), " ", KID, TestKeys.privatePem(TestKeys.ISSUER), null, null, null).audience()).isEqualTo("nova-api");
        assertThat(properties.toString()).contains("audience=nova-api").contains("privateKey=****").doesNotContain("BEGIN PRIVATE KEY");
    }
}
