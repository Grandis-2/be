package com.grandis.nova.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RSA 2048 비트 하한. JJWT 0.12.6 도 1024 비트 키는 서명(SignatureException) · 검증(WeakKeyException) 때 거부한다(실측) — 그 거부가 첫 로그인의
 * 500 이 되지 않게 설정 키는 기동에서, JWKS 로 받은 키는 받을 때 걸러 낸다.
 */
@DisplayName("RSA 키 길이 — 2048 비트 미만 거부")
class PemKeysStrengthTest {

    private static final KeyPair WEAK = generate(1024);
    private static final KeyPair STRONG = generate(2048);

    @Test
    @DisplayName("1024 비트 개인키 · 공개키는 읽을 때 거부한다. 2048 비트는 받는다")
    void weakConfiguredKeysAreRejected() {
        assertThatThrownBy(() -> PemKeys.parsePrivate(TestKeys.privatePem(WEAK)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 2048 bits").hasMessageContaining("1024");
        assertThatThrownBy(() -> PemKeys.parsePublic(TestKeys.publicPem(WEAK)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 2048 bits");
        assertThat(PemKeys.parsePrivate(TestKeys.privatePem(STRONG)).getModulus().bitLength()).isEqualTo(2048);
        assertThat(PemKeys.parsePublic(TestKeys.publicPem(STRONG)).getModulus().bitLength()).isEqualTo(2048);
    }

    @Test
    @DisplayName("JWKS 의 1024 비트 키는 버리고 2048 비트 키만 남긴다")
    void weakJwksKeysAreDropped() {
        String jwks = "{\"keys\":[" + jwk("weak", (RSAPublicKey) WEAK.getPublic()) + "," + jwk("strong", (RSAPublicKey) STRONG.getPublic()) + "]}";
        assertThat(JwtKeyRing.parseJwks(jwks)).containsOnlyKeys("strong");
    }

    private static String jwk(String kid, RSAPublicKey key) {
        return "{\"kty\":\"RSA\",\"kid\":\"" + kid + "\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\"" + b64(key.getModulus().toByteArray())
                + "\",\"e\":\"" + b64(key.getPublicExponent().toByteArray()) + "\"}";
    }

    private static String b64(byte[] bytes) {
        int start = bytes.length > 1 && bytes[0] == 0 ? 1 : 0;   // 부호 바이트 제거(무부호 big-endian)
        byte[] unsigned = java.util.Arrays.copyOfRange(bytes, start, bytes.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned);
    }

    private static KeyPair generate(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
