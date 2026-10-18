package com.grandis.nova.member.auth.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * 회원 리프레시 토큰의 원문과 지문.
 *
 * 원문은 JWT 가 아니라 **256비트 난수**다. 리프레시는 발급자만 검증하므로 자기설명적일 필요가 없고, 자기설명적이면 폐기 장치를
 * 따로 붙여야 한다. 원문은 HttpOnly 쿠키로만 나가고 저장소에는 SHA-256 만 남는다 — 저장소가 통째로 유출돼도 그 값으로 재발급할 수 없다.
 *
 * 해시에 솔트·반복을 쓰지 않는다. 비밀번호와 달리 원문이 우리가 만든 256비트 난수라 사전·무차별 대입의 대상이 아니고,
 * 재발급마다 해시를 한 번 더 도는 비용만 는다. (OWASP 도 고엔트로피 토큰에는 단순 해시를 권한다.)
 */
public final class RefreshTokens {

    /**
     * 회전 유예 — 교체 뒤 이 시간 안에 같은 옛 리프레시가 오고 그것이 만든 다음 토큰이 아직 안 쓰였으면 재사용 탐지 대신 다시 회전한다. 새로고침을 연타하면
     * 브라우저가 앞 재발급 응답을 버려 새 쿠키가 저장되지 않기 때문이다. 회원(DB) · 관리자(Redis) 가 같은 값을 쓴다. 30초는 Okta 의 기본 유예(0~60초).
     */
    public static final Duration ROTATION_GRACE = Duration.ofSeconds(30);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;          // 256비트
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private RefreshTokens() {
    }

    /** 새 원문. base64url 43자라 쿠키 값으로 그대로 나간다(퍼센트 인코딩 없음). */
    public static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /** 저장·조회 키. `refresh_tokens.token_hash` 는 binary(32) 라 32바이트 그대로 들어간다. */
    public static byte[] hash(String rawToken) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JRE spec", e);
        }
    }
}
