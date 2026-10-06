package com.grandis.nova.waitingroom.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * 액세스 토큰 검증 설정. 키 이름은 다른 서비스(common:security)와 같다 — 같은 환경 변수로 배포한다.
 * 키 소스는 jwk-set-uri(member 의 JWKS) 나 public-keys(kid → X.509 PEM) 중 하나 이상이다.
 * 생성자 예외에 설정 값을 싣지 않는다 — 부팅 로그에 키가 남지 않게.
 */
@ConfigurationProperties("jwt")
public record JwtProperties(
        String issuer,
        String audience,
        String jwkSetUri,
        Boolean jwkSetAllowHttp,
        Map<String, String> publicKeys,
        Duration clockSkew
) {

    static final String DEFAULT_AUDIENCE = "nova-api";
    static final Duration DEFAULT_CLOCK_SKEW = Duration.ofSeconds(30);

    public JwtProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("jwt.issuer is required");
        }
        audience = audience == null || audience.isBlank() ? DEFAULT_AUDIENCE : audience;
        jwkSetUri = jwkSetUri == null || jwkSetUri.isBlank() ? null : jwkSetUri;
        jwkSetAllowHttp = jwkSetAllowHttp != null && jwkSetAllowHttp;
        publicKeys = publicKeys == null ? Map.of() : Map.copyOf(publicKeys);
        clockSkew = clockSkew == null ? DEFAULT_CLOCK_SKEW : clockSkew;
        if (jwkSetUri == null && publicKeys.isEmpty()) {
            throw new IllegalArgumentException("jwt: no key source — set jwt.jwk-set-uri or jwt.public-keys");
        }
        // JWKS 경로를 가로채면 자기 공개키를 심어 아무 토큰이나 통과시킬 수 있다
        if (jwkSetUri != null && !jwkSetUri.startsWith("https://")
                && !(jwkSetAllowHttp && jwkSetUri.startsWith("http://"))) {
            throw new IllegalArgumentException(
                    "jwt.jwk-set-uri must use https (or set jwt.jwk-set-allow-http=true explicitly for in-VPC plain http)");
        }
        if (clockSkew.isNegative()) {
            throw new IllegalArgumentException("jwt.clock-skew must not be negative");
        }
    }

    @Override
    public String toString() {
        return "JwtProperties[issuer=" + issuer + ", audience=" + audience + ", jwkSetUri=" + jwkSetUri
                + ", jwkSetAllowHttp=" + jwkSetAllowHttp + ", publicKeys=" + publicKeys.keySet()
                + ", clockSkew=" + clockSkew + "]";
    }
}
