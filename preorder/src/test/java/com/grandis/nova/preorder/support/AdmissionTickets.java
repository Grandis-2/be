package com.grandis.nova.preorder.support;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 테스트 전용 입장권 발급기. 운영 코드에는 발급이 없다 — 발급은 게이트웨이의 일이다.
 * waiting SignedToken 과 같은 규칙이며, AdmissionTicketVerifierTest 가 waiting 으로 만든 벡터와 같은 문자열이 나오는지 확인한다.
 */
public final class AdmissionTickets {

    public static final long TTL_SECONDS = 120;
    public static final long WINDOW_SECONDS = 30;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final char FIELD = (char) 0x1f;

    private AdmissionTickets() {
    }

    /** 테스트 공용 비밀로 발급. */
    public static String issue(Long productId, Long customerId, Instant now) {
        return issue(PreorderIntegrationTest.ADMISSION_TICKET_SECRET, productId, customerId, now);
    }

    public static String issue(String secret, Long productId, Long customerId, Instant now) {
        long exp = now.getEpochSecond() / WINDOW_SECONDS * WINDOW_SECONDS + TTL_SECONDS;
        return issueRaw(secret, "et_", String.valueOf(productId) + FIELD + customerId + FIELD + exp);
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
