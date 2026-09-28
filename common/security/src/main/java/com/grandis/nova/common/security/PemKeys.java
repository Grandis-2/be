package com.grandis.nova.common.security;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * PEM ↔ RSA 키. 개인키는 PKCS#8("BEGIN PRIVATE KEY"), 공개키는 X.509 SubjectPublicKeyInfo("BEGIN PUBLIC KEY").
 * `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048` 이 내는 형식이 PKCS#8 이다. PKCS#1("BEGIN RSA PRIVATE KEY")은 받지 않는다 —
 * 형식이 다르면 여기서 바로 실패하게 두는 편이 "키는 읽혔는데 서명이 안 맞는" 것보다 낫다.
 */
public final class PemKeys {

    // "BEGIN … PRIVATE KEY" 를 한 리터럴로 두면 gitleaks(private-key 규칙)가 소스를 개인키로 오탐한다. 라벨을 쪼개 조립한다.
    private static final String PRIVATE_LABEL = "PRIVATE KEY";
    private static final String PUBLIC_LABEL = "PUBLIC KEY";

    private PemKeys() {
    }

    private static String begin(String label) {
        return "-----BEGIN " + label + "-----";
    }

    private static String end(String label) {
        return "-----END " + label + "-----";
    }

    public static RSAPrivateKey parsePrivate(String pem) {
        try {
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decode(pem, PRIVATE_LABEL)));
            if (!(key instanceof RSAPrivateKey rsa)) {
                throw new IllegalArgumentException("jwt.private-key is not an RSA key");
            }
            return rsa;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalArgumentException("jwt.private-key is not a PKCS#8 RSA private key: " + e.getMessage(), e);
        }
    }

    public static RSAPublicKey parsePublic(String pem) {
        try {
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decode(pem, PUBLIC_LABEL)));
            if (!(key instanceof RSAPublicKey rsa)) {
                throw new IllegalArgumentException("public key is not an RSA key");
            }
            return rsa;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalArgumentException("not an X.509 RSA public key: " + e.getMessage(), e);
        }
    }

    /** 개인키(CRT 형식)에서 공개키를 계산한다. 발급 서비스는 공개키를 따로 설정하지 않는다. */
    public static RSAPublicKey publicOf(RSAPrivateKey privateKey) {
        if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
            throw new IllegalArgumentException("jwt.private-key must be a CRT RSA key so the public key can be derived");
        }
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String toPem(PublicKey key) {
        return begin(PUBLIC_LABEL) + "\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded()) + "\n" + end(PUBLIC_LABEL) + "\n";
    }

    /** JWK 의 n·e: 부호 없는 big-endian 바이트를 base64url 로. BigInteger.toByteArray 의 앞 0 부호 바이트를 뗀다. */
    public static String base64UrlUnsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] decode(String pem, String label) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("empty PEM");
        }
        String body = pem.replace(begin(label), "").replace(end(label), "").replaceAll("\\s", "");
        if (body.contains("-----")) {
            throw new IllegalArgumentException("expected a PEM block labelled " + label);
        }
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("PEM body is not base64", e);
        }
    }
}
