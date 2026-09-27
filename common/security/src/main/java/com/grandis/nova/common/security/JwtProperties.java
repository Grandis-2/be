package com.grandis.nova.common.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT 발급·검증 설정. 서명은 RS256 — 개인키는 발급 서비스(member) 하나만 갖고, 검증 서비스는 공개키만 갖는다.
 * HS512 공유 키를 버린 이유: 검증하는 서비스 넷이 전부 서명도 할 수 있는 키를 갖게 되어 한 서비스가 뚫리면 토큰 위조가 됐다.
 *
 * 키 소스 셋 중 하나 이상이 있어야 기동한다.
 * - `private-key`(PKCS#8 PEM) + `key-id`: 발급 서비스. 공개키는 개인키에서 계산해 JWKS 로 게시한다.
 * - `public-keys`(kid → X.509 PEM): 정적 공개키. 발급 서비스에서는 **이전 키**(교체 중 검증용), 검증 서비스에서는 JWKS 대신 쓸 수 있다.
 * - `jwk-set-uri`: 검증 서비스가 발급 서비스의 `/.well-known/jwks.json` 을 받아 캐시한다. 모르는 kid 가 오면 한 번 다시 받는다.
 *
 * `jwk-set-uri` 는 https 여야 한다. 이 경로를 가로채면 자기 공개키를 심어 토큰을 통째로 위조할 수 있다 — RS256 으로 없앤 위험이 네트워크로 자리를 옮기는 것.
 * VPC 안 서비스 간 호출에 TLS 가 없는 동안은 `jwk-set-allow-http: true` 를 **명시**해야 http 를 받는다(보안 그룹으로 경로를 제한하는 것을 전제로 한 결정).
 * 기본값으로 http 가 들어가는 일은 없다.
 *
 * **금지선: `privateKey`·`publicKeys` 에 `@NotBlank`·`@Pattern` 같은 바인딩 검증을 붙이지 않는다.** 붙이면 Boot 의 BindValidationFailureAnalyzer 가
 * 실패한 속성의 **값 전문을 부팅 로그에 찍는다** — 개인키가 로그에 남는다. 지금 안 새는 이유는 키 속성이 전부 String 이라 스칼라 변환 실패가 없고,
 * 생성자에서 던지는 예외에는 속성 값이 안 붙기 때문이다. 검사는 전부 이 생성자 안에서, 값을 메시지에 넣지 않고 한다.
 *
 * audience(aud)는 RFC 8725 §3.9 — 발급자 하나에 받는 서비스가 넷이라 "이 토큰이 우리 API 용" 을 토큰 안에 적고 검증한다. 기본 "nova-api".
 * 만료는 설정값이다(예시: 액세스 30m · 리프레시 14d). 0 이나 음수는 바인딩을 통과하면 "발급은 되는데 즉시 만료" 가 되어 로그인만 100% 실패하고
 * 부팅 로그에 단서가 없으므로 생성자에서 막는다. 예시 파일의 자리표시자(`CHANGE_ME…`)도 여기서 걸린다.
 */
@Validated
@ConfigurationProperties("jwt")
public record JwtProperties(
        @NotBlank String issuer,
        @NotNull Duration accessTokenValidity,
        @NotNull Duration refreshTokenValidity,
        String audience,
        String keyId,
        String privateKey,
        Map<String, String> publicKeys,
        String jwkSetUri,
        Boolean jwkSetAllowHttp
) {

    public static final String DEFAULT_AUDIENCE = "nova-api";

    public JwtProperties {
        if (audience == null || audience.isBlank()) {
            audience = DEFAULT_AUDIENCE;
        }
        if (publicKeys == null) {
            publicKeys = Map.of();
        }
        boolean issuerMode = privateKey != null && !privateKey.isBlank();
        if (issuerMode && privateKey.startsWith("CHANGE_ME")) {
            throw new IllegalArgumentException("jwt.private-key is still the example placeholder; generate one with `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048`");
        }
        if (issuerMode && (keyId == null || keyId.isBlank())) {
            throw new IllegalArgumentException("jwt.key-id is required when jwt.private-key is set (the kid goes into every token header)");
        }
        if (!issuerMode && publicKeys.isEmpty() && (jwkSetUri == null || jwkSetUri.isBlank())) {
            throw new IllegalArgumentException("jwt: no key source — set jwt.private-key (issuer) or jwt.public-keys / jwt.jwk-set-uri (verifier)");
        }
        if (jwkSetAllowHttp == null) {
            jwkSetAllowHttp = false;
        }
        if (jwkSetUri != null && !jwkSetUri.isBlank() && !jwkSetUri.startsWith("https://") && !jwkSetAllowHttp) {
            throw new IllegalArgumentException("jwt.jwk-set-uri must use https (or set jwt.jwk-set-allow-http=true explicitly for in-VPC plain http)");
        }
        requirePositive(accessTokenValidity, "jwt.access-token-validity");
        requirePositive(refreshTokenValidity, "jwt.refresh-token-validity");
    }

    /** 이 서비스가 토큰을 발급하는가(개인키가 있는가). */
    public boolean issues() {
        return privateKey != null && !privateKey.isBlank();
    }

    private static void requirePositive(Duration value, String name) {
        if (value != null && (value.isZero() || value.isNegative())) {
            throw new IllegalArgumentException(name + " must be positive, was " + value);
        }
    }

    /** 개인키가 로그·오류 메시지에 평문으로 찍히지 않게 한다. */
    @Override
    public String toString() {
        return "JwtProperties[issuer=" + issuer + ", audience=" + audience + ", keyId=" + keyId
                + ", privateKey=" + (issues() ? "****(" + privateKey.length() + " chars)" : "none")
                + ", publicKeys=" + publicKeys.keySet() + ", jwkSetUri=" + jwkSetUri + ", jwkSetAllowHttp=" + jwkSetAllowHttp
                + ", accessTokenValidity=" + accessTokenValidity + ", refreshTokenValidity=" + refreshTokenValidity + "]";
    }
}
