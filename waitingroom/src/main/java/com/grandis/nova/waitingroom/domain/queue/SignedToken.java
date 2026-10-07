package com.grandis.nova.waitingroom.domain.queue;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HMAC 서명 토큰 — prefix + base64url(productKey US customerId US exp) + "." + base64url(HMAC(prefix + payload)).
 * preorder 의 입장권 검증과 형식이 같아야 한다. 검증에 저장소 조회가 없어 요청 경로가 Redis 를 치지 않는다.
 */
public final class SignedToken {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_SECRET_LENGTH = 16;
    /** 인증 없는 요청 하나가 키 수만큼 HMAC 을 돌리므로 옛 키 수를 묶는다. */
    private static final int MAX_PREVIOUS = 2;
    private static final char SEPARATOR = '.';
    /** 정상 토큰은 200자 안쪽이다(UUID 두 개 + 만료). 인증 없는 요청이 긴 입력으로 HMAC 을 여러 번 돌리지 못하게 먼저 자른다. */
    private static final int MAX_TOKEN_LENGTH = 512;
    /** 필드 구분자(U+001F). 식별자에 들어갈 수 없는 글자라 경계가 옮겨지지 않는다. */
    private static final char FIELD = (char) 0x1f;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final String prefix;
    private final long ttlSec;
    private final long windowSec;
    private final byte[] secret;
    /** 검증에서만 받는 옛 키. 발급에는 안 쓴다 — 옛 키로 내면 그 키를 뺀 뒤 방금 낸 토큰이 죽는다. */
    private final List<byte[]> previous;
    /** 옛 키를 여기까지만 받는다. 그 키로 낸 마지막 토큰이 죽는 때다. */
    private final Instant acceptPreviousUntil;
    private final AtomicLong acceptedByPrevious = new AtomicLong();

    private SignedToken(String prefix, long ttlSec, long windowSec, byte[] secret, List<byte[]> previous,
                        Instant acceptPreviousUntil) {
        this.prefix = prefix;
        this.ttlSec = ttlSec;
        this.windowSec = windowSec;
        this.secret = secret;
        this.previous = previous;
        this.acceptPreviousUntil = acceptPreviousUntil;
    }

    /** @param rolloutEndsAt 키 교체 배포가 모두 끝나는 때. 옛 키가 있으면 필수다 */
    public static SignedToken of(String prefix, long ttlSec, long windowSec, String secret,
                                 List<String> previous, Instant rolloutEndsAt) {
        if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException("토큰 비밀키는 %d자 이상이어야 한다".formatted(MIN_SECRET_LENGTH));
        }
        for (String old : previous) {
            if (old == null || old.length() < MIN_SECRET_LENGTH) {
                throw new IllegalArgumentException("옛 키도 %d자 이상이어야 한다".formatted(MIN_SECRET_LENGTH));
            }
            if (old.equals(secret)) {
                throw new IllegalArgumentException("현재 키를 옛 키로 또 적을 수 없다");
            }
        }
        if (Set.copyOf(previous).size() != previous.size()) {
            throw new IllegalArgumentException("옛 키가 중복이다");
        }
        if (previous.size() > MAX_PREVIOUS) {
            throw new IllegalArgumentException("옛 키는 %d개까지다: %d개".formatted(MAX_PREVIOUS, previous.size()));
        }
        if (!previous.isEmpty() && rolloutEndsAt == null) {
            throw new IllegalArgumentException("옛 키를 받으려면 교체 배포가 끝나는 때를 적어야 한다");
        }
        // 창이 0 이면 발급이 0 으로 나누고, 창이 수명보다 길면 방금 받은 토큰이 이미 만료다
        if (windowSec < 1 || ttlSec < 1 || windowSec > ttlSec) {
            throw new IllegalArgumentException("수명과 창은 양수이고 창 <= 수명이어야 한다: ttl=%d window=%d"
                    .formatted(ttlSec, windowSec));
        }
        return new SignedToken(prefix, ttlSec, windowSec, secret.getBytes(StandardCharsets.UTF_8),
                previous.stream().map(key -> key.getBytes(StandardCharsets.UTF_8)).toList(),
                rolloutEndsAt == null ? null : rolloutEndsAt.plusSeconds(ttlSec + windowSec));
    }

    /** 같은 창(window) 안에서는 같은 사람에게 같은 값을 준다 — 폴링마다 토큰이 바뀌지 않게. */
    public String issue(String productKey, String customerId, Instant now) {
        String payload = ENCODER.encodeToString(
                (productKey + FIELD + customerId + FIELD + expiry(now)).getBytes(StandardCharsets.UTF_8));
        return prefix + payload + SEPARATOR + new String(signature(secret, payload), StandardCharsets.UTF_8);
    }

    /**
     * 이 모델의 유효한 토큰이면 회원 식별자를 돌려준다. 서명을 먼저 보고, 실패 사유는 나누지 않는다 —
     * 어느 칸이 틀렸는지 알려 주면 맞추는 데 쓰인다.
     */
    public Optional<String> verify(String token, String productKey, Instant now) {
        return authenticate(token, productKey, now).filter(holder -> !holder.expired(now)).map(Holder::customerId);
    }

    /**
     * 서명과 모델이 맞으면 회원과 만료 시각을 돌려준다. 만료를 거절과 나눠 알려 준다 — 만료된 입장권으로 같은 접수를
     * 다시 보낸 것인지는 받는 쪽(preorder)이 판단한다.
     */
    public Optional<Holder> authenticate(String token, String productKey, Instant now) {
        if (token == null || token.length() > MAX_TOKEN_LENGTH || !token.startsWith(prefix)) {
            return Optional.empty();
        }
        int mark = token.indexOf(SEPARATOR);
        if (mark < 0) {
            return Optional.empty();
        }
        String payload = token.substring(prefix.length(), mark);
        Match match = matchOf(payload, token.substring(mark + 1), now);
        if (match == Match.NONE) {
            return Optional.empty();
        }
        String[] parts = fields(payload);
        if (parts.length != 3 || !parts[0].equals(productKey)) {
            return Optional.empty();
        }
        OptionalLong exp = epochSecond(parts[2]);
        if (exp.isEmpty()) {
            return Optional.empty();
        }
        Holder holder = new Holder(parts[1], Instant.ofEpochSecond(exp.getAsLong()));
        if (match == Match.PREVIOUS && !holder.expired(now)) {
            acceptedByPrevious.incrementAndGet();
        }
        return Optional.of(holder);
    }

    /** 이 시각에 낸 토큰이 만료되는 때. 같은 창에서 낸 토큰은 모두 같다. */
    public Instant expiresAt(Instant issuedAt) {
        return Instant.ofEpochSecond(expiry(issuedAt));
    }

    /** 옛 키로 받아 준 누적 횟수. 더 안 오르는 때가 옛 키를 빼도 되는 때다. */
    public long acceptedByPrevious() {
        return acceptedByPrevious.get();
    }

    /**
     * 서명을 디코드하지 않고 우리가 낸 표기 그대로와 비교한다. Base64 디코더는 패딩 · 끝 비트가 다른 표기를 같은 값으로 읽어
     * 같은 입장권에 다른 문자열이 생긴다(소비 기록을 문자열로 하면 재사용된다). 맞은 뒤에도 옛 키를 모두 계산한다.
     */
    private Match matchOf(String payload, String presented, Instant now) {
        byte[] presentedBytes = presented.getBytes(StandardCharsets.UTF_8);
        boolean current = MessageDigest.isEqual(signature(secret, payload), presentedBytes);
        if (acceptPreviousUntil == null || !now.isBefore(acceptPreviousUntil)) {
            return current ? Match.CURRENT : Match.NONE;
        }
        boolean byPrevious = false;
        for (byte[] old : previous) {
            byPrevious |= MessageDigest.isEqual(signature(old, payload), presentedBytes);
        }
        if (current) {
            return Match.CURRENT;
        }
        return byPrevious ? Match.PREVIOUS : Match.NONE;
    }

    /** 만료가 아니라 발급 시각을 창 단위로 끊는다 — 창 끝에 받은 토큰도 최소 ttl - window 는 산다. */
    private long expiry(Instant now) {
        return now.getEpochSecond() / windowSec * windowSec + ttlSec;
    }

    /** base64url(HMAC(prefix + payload)) 의 ASCII 바이트. 접두를 서명에 넣어 대기 토큰을 입장권으로 바꿔 쓸 수 없다. */
    private byte[] signature(byte[] key, String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            byte[] digest = mac.doFinal((prefix + payload).getBytes(StandardCharsets.UTF_8));
            return ENCODER.encode(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("토큰 서명 실패", e);
        }
    }

    /** 서명이 맞은 뒤라 우리가 만든 문자열이지만, 키가 샜을 때도 예외 대신 거절로 끝나게 한다. */
    private static String[] fields(String payload) {
        try {
            return new String(DECODER.decode(payload), StandardCharsets.UTF_8).split(String.valueOf(FIELD), -1);
        } catch (IllegalArgumentException e) {
            return new String[0];
        }
    }

    private static OptionalLong epochSecond(String exp) {
        try {
            return OptionalLong.of(Long.parseLong(exp));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    private enum Match { NONE, CURRENT, PREVIOUS }

    /** 서명과 모델이 맞은 토큰의 회원과 만료 시각. */
    public record Holder(String customerId, Instant expiresAt) {

        public boolean expired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }
}
