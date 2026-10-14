package com.grandis.nova.preorder.support;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 테스트 전용 입장권 발급기. 운영 코드에는 발급이 없다 — 발급은 게이트웨이의 일이다.
 * waiting SignedToken 과 같은 규칙이며(발급 시각 칸이 없는 세 칸 · 있는 네 칸), AdmissionTicketVerifierTest 가 waiting 으로 만든 벡터와 같은 문자열이 나오는지 확인한다.
 */
public final class AdmissionTickets {

    public static final long TTL_SECONDS = 120;
    public static final long WINDOW_SECONDS = 30;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final char FIELD = (char) 0x1f;

    private AdmissionTickets() {
    }

    /** 테스트 공용 비밀로 발급. */
    public static String issue(UUID productId, UUID customerId, Instant now) {
        return issue(PreorderIntegrationTest.ADMISSION_TICKET_SECRET, productId, customerId, now);
    }

    public static String issue(String secret, UUID productId, UUID customerId, Instant now) {
        long exp = now.getEpochSecond() / WINDOW_SECONDS * WINDOW_SECONDS + TTL_SECONDS;
        return issueRaw(secret, "et_", productId.toString() + FIELD + customerId + FIELD + exp);
    }

    /** 발급 시각을 실은 네 칸 입장권. 창으로 내리지 않아 만료는 발급 시각 + 수명이다. */
    public static String issueWithIssuedAt(UUID productId, UUID customerId, Instant issuedAt, Duration ttl) {
        return issueWithIssuedAt(PreorderIntegrationTest.ADMISSION_TICKET_SECRET, productId, customerId, issuedAt, ttl);
    }

    public static String issueWithIssuedAt(String secret, UUID productId, UUID customerId, Instant issuedAt,
                                           Duration ttl) {
        long iat = issuedAt.getEpochSecond();
        return issueRaw(secret, "et_",
                productId.toString() + FIELD + customerId + FIELD + (iat + ttl.toSeconds()) + FIELD + iat);
    }

    /** 페이로드를 그대로 서명한다. 모양이 틀린 입장권을 만들 때 쓴다. */
    public static String issueRaw(String secret, String prefix, String claims) {
        String payload = ENCODER.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal((prefix + payload).getBytes(StandardCharsets.UTF_8));
            return prefix + payload + "." + ENCODER.encodeToString(signature);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
