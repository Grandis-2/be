package com.grandis.nova.waitingroom.auth;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Flux;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * member 가 발급한 액세스 토큰 검증기를 만든다. 알고리즘은 RS256 으로 고정한다 —
 * 토큰 머리의 alg 를 믿으면 none · HS256 으로 바꿔치기한 토큰이 통과한다.
 */
final class JwtDecoderFactory {

    static final int MIN_RSA_BITS = 2048;

    static final String ACCESS_TOKEN_TYP = "at+jwt";
    static final String CLAIM_TYPE = "type";
    static final String CLAIM_ROLE = "role";
    static final String CLAIM_SESSION_ID = "sid";
    static final String ACCESS = "ACCESS";
    static final Set<String> ROLES = Set.of("USER", "ADMIN");

    private JwtDecoderFactory() {
    }

    /** @param remote member JWKS 캐시. jwk-set-uri 가 없으면 null */
    static ReactiveJwtDecoder create(JwtProperties properties, JwkSetCache remote, Clock clock) {
        Map<String, JWK> staticKeys = properties.publicKeys().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> new RSAKey.Builder(parse(entry.getKey(), entry.getValue())).keyID(entry.getKey()).build()));
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSource(keySource(staticKeys, remote))
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(validator(properties, clock));
        // 키를 못 받은 것(IllegalStateException)은 토큰 탓이 아니다 — 401 이 아니라 503 으로 가게 한다
        return token -> decoder.decode(token)
                .onErrorMap(IllegalStateException.class, SigningKeysUnavailableException::new);
    }

    /** 설정의 공개키를 먼저 보고, 모르는 kid 만 JWKS 에서 찾는다. */
    private static Function<SignedJWT, Flux<JWK>> keySource(Map<String, JWK> staticKeys, JwkSetCache remote) {
        return jwt -> {
            String kid = jwt.getHeader().getKeyID();
            // kid 없는 토큰은 고를 키가 없다 — 받아 둔 키 전부로 맞춰 보지 않는다
            if (kid == null || kid.isBlank()) {
                return Flux.empty();
            }
            JWK key = staticKeys.get(kid);
            if (key != null) {
                return Flux.just(key);
            }
            return remote == null ? Flux.empty() : remote.apply(jwt);
        };
    }

    private static OAuth2TokenValidator<Jwt> validator(JwtProperties properties, Clock clock) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator(properties.clockSkew());
        timestamps.setClock(clock);
        // 만료가 없는 토큰은 새면 영원히 유효하다
        timestamps.setAllowEmptyExpiryClaim(false);
        return JwtValidators.createDefaultWithValidators(List.of(
                new JwtTypeValidator(ACCESS_TOKEN_TYP),
                timestamps,
                new JwtIssuerValidator(properties.issuer()),
                new JwtAudienceValidator(properties.audience()),
                text(JwtClaimNames.SUB),
                text(JwtClaimNames.JTI),
                text(CLAIM_SESSION_ID),
                // 리프레시 토큰으로 API 를 부르지 못하게
                new JwtClaimValidator<Object>(CLAIM_TYPE, ACCESS::equals),
                new JwtClaimValidator<Object>(CLAIM_ROLE, ROLES::contains)));
    }

    /** 문자열이 아닌 값(숫자 등)이 와도 ClassCastException 이 아니라 검증 실패로 끝나게 한다. */
    private static JwtClaimValidator<Object> text(String claim) {
        return new JwtClaimValidator<>(claim, value -> value instanceof String text && !text.isBlank());
    }

    private static RSAPublicKey parse(String kid, String pem) {
        String body = pem.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
        RSAPublicKey key;
        try {
            key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new IllegalArgumentException("jwt.public-keys." + kid + " is not an X.509 RSA public key");
        }
        if (key.getModulus().bitLength() < MIN_RSA_BITS) {
            throw new IllegalArgumentException("jwt.public-keys." + kid + " is shorter than " + MIN_RSA_BITS + " bits");
        }
        return key;
    }
}
