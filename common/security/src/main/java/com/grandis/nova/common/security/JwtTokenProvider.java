package com.grandis.nova.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * JWT 를 만들고 파싱한다. 발급 정책(sid 생성·회전·폐기)은 member 의 TokenService 가 갖고, 여기는 서명과 클레임만 안다.
 *
 * 클레임은 여덟이다: sub · sid · jti · role · type · iat · exp · aud(RFC 8725 §3.9).
 * 헤더 typ 은 토큰 종류를 명시한다(RFC 8725 §3.11·§3.12, RFC 9068): ACCESS 는 "at+jwt", REFRESH 는 "rt+jwt". 파싱은 typ 과 type 클레임이
 * 서로 맞는지도 본다 — 종류가 다른 토큰의 검증 규칙이 배타적이어야 한다는 요구를 헤더와 클레임 두 겹으로 지킨다.
 * 파싱은 서명·issuer·audience·만료·필수 클레임·typ 을 전부 검사하고, 하나라도 어긋나면 InvalidTokenException 하나로 뭉친다.
 * jjwt 가 던지는 것(서명·형식·타입 변환)도, 이 클래스의 필수 클레임 검사도 전부 그 예외로 나간다. 호출자는 이유를 구분하지 않는다.
 *
 * 서명은 RS256. 발급은 개인키(발급 서비스만), 검증은 헤더 kid 로 고른 공개키(JwtKeyRing). 개인키가 없는 서비스에서 create 를 부르면
 * IllegalStateException — 설정 실수가 첫 발급에서 바로 드러난다. alg 는 파싱 뒤에도 RS256 인지 본다(RFC 8725 §3.1, 키 혼동 방지).
 *
 * 시각은 주입된 Clock 에서만 온다. jjwt 의 만료 검사에도 같은 시계를 넣는다.
 * 만료 경계는 이 클래스가 정한다: exp 와 같은 순간이면 만료다(exp 는 "이때부터 무효"). jjwt 의 판정에 기대지 않고 직접 비교한다.
 * iat·exp 는 JWT 규격상 epoch 초라 밀리초 아래가 잘린다. TokenClaims 의 시각은 초 정밀도로 다룬다.
 */
@Component
public class JwtTokenProvider {

    static final String CLAIM_SESSION_ID = "sid";
    static final String CLAIM_ROLE = "role";
    static final String CLAIM_TYPE = "type";

    static final String ALG = "RS256";

    private final String issuer;
    private final String audience;
    private final JwtKeyRing keys;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtTokenProvider(JwtProperties properties, JwtKeyRing keys, Clock clock) {
        this.issuer = properties.issuer();
        this.audience = properties.audience();
        this.keys = keys;
        this.properties = properties;
        this.clock = clock;
    }

    /** 타입에 맞는 만료(설정값. 예시는 액세스 30m · 리프레시 14d)로 새 토큰을 만든다. jti 는 매번 새로 난다. */
    public String create(String subject, Role role, UUID sessionId, TokenType type) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(type == TokenType.ACCESS
                ? properties.accessTokenValidity()
                : properties.refreshTokenValidity());
        return create(subject, role, sessionId, type, now, expiresAt);
    }

    /**
     * 만료 시각을 지정해 만든다. 리프레시 회전용이다. 회전은 원 토큰의 만료를 그대로 넘겨 부르므로 절대 만료가 늘지 않는 것은
     * 호출자(TokenService)가 지킨다. 이 클래스는 지정한 만료가 이미 지났는지만 본다 — 만료된 세션은 다시 로그인해야 한다.
     */
    public String create(String subject, Role role, UUID sessionId, TokenType type, Instant expiresAt) {
        Instant now = clock.instant();
        if (!expiresAt.isAfter(now)) {
            throw new InvalidTokenException("expiresAt is not after now");
        }
        return create(subject, role, sessionId, type, now, expiresAt);
    }

    private String create(String subject, Role role, UUID sessionId, TokenType type, Instant now, Instant expiresAt) {
        return Jwts.builder()
                .header().type(typ(type)).keyId(keys.keyId()).and()
                .id(UUID.randomUUID().toString())
                .issuer(issuer)
                .audience().add(audience).and()
                .subject(subject)
                .claim(CLAIM_SESSION_ID, sessionId.toString())
                .claim(CLAIM_ROLE, role.name())
                .claim(CLAIM_TYPE, type.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(keys.signingKey(), Jwts.SIG.RS256)
                .compact();
    }

    /**
     * 서명·issuer·형식을 jjwt 가 검사하고, 필수 클레임 일곱 개와 만료 경계는 여기서 검사한다.
     * 타입(ACCESS/REFRESH)을 가려 쓰는 것은 호출자 몫이다. 필터는 ACCESS 만, 재발급은 REFRESH 만 받는다.
     */
    public TokenClaims parse(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("token is blank");
        }
        try {
            Jws<Claims> jws = Jwts.parser()
                    .keyLocator(keys.locator())
                    .requireIssuer(issuer)
                    .requireAudience(audience)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token);
            if (!ALG.equals(jws.getHeader().getAlgorithm())) {
                throw new InvalidTokenException("alg is not " + ALG);
            }
            Claims claims = jws.getPayload();

            // 비표준 클레임은 jjwt 의 타입 변환(get(name, String.class) → RequiredTypeException)에 기대지 않고
            // Object 로 꺼내 직접 문자열화한다. 그래야 실패 이유가 "malformed claim sid" 처럼 클레임 이름으로 남는다.
            // 실측: JwtTokenProviderTest.sessionIdNotAString. 클레임 추출 전체를 try 안에 두는 것도 같은 이유다.
            String subject = requireText(claims.getSubject(), "sub");
            UUID sessionId = requireUuid(stringClaim(claims, CLAIM_SESSION_ID), CLAIM_SESSION_ID);
            UUID tokenId = requireUuid(claims.getId(), "jti");
            Role role = requireEnum(stringClaim(claims, CLAIM_ROLE), Role.class, CLAIM_ROLE);
            TokenType type = requireEnum(stringClaim(claims, CLAIM_TYPE), TokenType.class, CLAIM_TYPE);
            Instant issuedAt = requireInstant(claims.getIssuedAt(), "iat");
            Instant expiresAt = requireInstant(claims.getExpiration(), "exp");
            if (!typ(type).equals(jws.getHeader().getType())) {
                throw new InvalidTokenException("typ header does not match type claim");
            }

            // jjwt 도 만료를 보지만 경계(같은 순간)의 판정을 이 클래스가 소유하기 위해 직접 비교한다.
            if (!expiresAt.isAfter(clock.instant())) {
                throw new InvalidTokenException("expired");
            }
            return new TokenClaims(subject, sessionId, tokenId, role, type, issuedAt, expiresAt);
        } catch (InvalidTokenException e) {
            throw e;
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("jwt rejected (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** RFC 9068 의 "at+jwt" 를 액세스에, 같은 꼴의 "rt+jwt" 를 리프레시에. 다른 종류의 JWT 와 섞이지 않게 하는 이름표다. */
    static String typ(TokenType type) {
        return type == TokenType.ACCESS ? "at+jwt" : "rt+jwt";
    }

    private static String stringClaim(Claims claims, String name) {
        Object value = claims.get(name);
        return value == null ? null : String.valueOf(value);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new InvalidTokenException("missing claim " + name);
        }
        return value;
    }

    private static UUID requireUuid(String value, String name) {
        try {
            return UUID.fromString(requireText(value, name));
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("malformed claim " + name);
        }
    }

    private static <E extends Enum<E>> E requireEnum(String value, Class<E> type, String name) {
        try {
            return Enum.valueOf(type, requireText(value, name));
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("unknown claim value " + name);
        }
    }

    private static Instant requireInstant(Date value, String name) {
        if (value == null) {
            throw new InvalidTokenException("missing claim " + name);
        }
        return value.toInstant();
    }
}
