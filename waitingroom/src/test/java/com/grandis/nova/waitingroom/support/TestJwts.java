package com.grandis.nova.waitingroom.support;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** member 와 같은 형식의 액세스 토큰을 만든다. 키 쌍은 JVM 하나에 한 번 만든다. */
public final class TestJwts {

    public static final String KID = "test-1";
    public static final String ISSUER = "nova";
    public static final String AUDIENCE = "nova-api";
    /** 입장권 · 대기 토큰 서명 키(시험용). */
    public static final String TOKEN_SECRET = "test-token-secret-0123456789";

    private static final RSAKey KEY = generate(KID);

    private TestJwts() {
    }

    /** jwt.public-keys 에 넣을 X.509 PEM. */
    public static String publicKeyPem() {
        return pem(KEY);
    }

    public static RSAKey key() {
        return KEY;
    }

    public static RSAKey generate(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String pem(RSAKey key) {
        try {
            return "-----BEGIN PUBLIC KEY-----\n"
                    + Base64.getMimeEncoder().encodeToString(key.toRSAPublicKey().getEncoded())
                    + "\n-----END PUBLIC KEY-----";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String user(String subject, Instant now) {
        return builder(subject, now).sign();
    }

    public static String admin(Instant now) {
        return builder("admin", now).role("ADMIN").sign();
    }

    public static Builder builder(String subject, Instant now) {
        return new Builder(subject, now);
    }

    public static final class Builder {

        private RSAKey signingKey = KEY;
        private String typ = "at+jwt";
        private String issuer = ISSUER;
        private String audience = AUDIENCE;
        private String subject;
        private String role = "USER";
        private String type = "ACCESS";
        private final Instant issuedAt;
        private Instant expiresAt;
        private boolean withKid = true;
        private final Set<String> omitted = new HashSet<>();

        private Builder(String subject, Instant now) {
            this.subject = subject;
            this.issuedAt = now;
            this.expiresAt = now.plusSeconds(900);
        }

        public Builder signingKey(RSAKey signingKey) {
            this.signingKey = signingKey;
            return this;
        }

        public Builder typ(String typ) {
            this.typ = typ;
            return this;
        }

        public Builder issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        public Builder audience(String audience) {
            this.audience = audience;
            return this;
        }

        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Builder role(String role) {
            this.role = role;
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        /** 클레임을 빼고 서명한다(sub · jti · sid · iat 등). */
        public Builder omit(String claim) {
            omitted.add(claim);
            return this;
        }

        public Builder withoutKid() {
            this.withKid = false;
            return this;
        }

        public String sign() {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(audience)
                    .subject(subject)
                    .jwtID(UUID.randomUUID().toString())
                    .claim("sid", UUID.randomUUID().toString())
                    .claim("role", role)
                    .claim("type", type)
                    .issueTime(Date.from(issuedAt));
            if (expiresAt != null) {
                claims.expirationTime(Date.from(expiresAt));
            }
            JWTClaimsSet.Builder kept = new JWTClaimsSet.Builder();
            claims.build().getClaims().forEach((name, value) -> {
                if (!omitted.contains(name)) {
                    kept.claim(name, value);
                }
            });
            claims = kept;
            JWSHeader.Builder header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(withKid ? signingKey.getKeyID() : null);
            if (typ != null) {
                header.type(new JOSEObjectType(typ));
            }
            try {
                SignedJWT jwt = new SignedJWT(header.build(), claims.build());
                JWSSigner signer = new RSASSASigner(signingKey);
                jwt.sign(signer);
                return jwt.serialize();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
