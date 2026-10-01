package com.grandis.nova.preorder.admission;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 대기열 입장권(et_) 검증. 게이트웨이가 발급하고 preorder 가 소비한다.
 * 규격: gateway-auth-tokens.md 5절 — 정본 구현(waiting SignedToken)과 비트 단위로 같아야 한다.
 *
 * <pre>
 * token      = "et_" + payloadB64 + "." + sigB64
 * payloadB64 = base64url_nopad(UTF8(productId + U+001F + customerId + U+001F + exp))
 * sigB64     = base64url_nopad(HMAC-SHA256(UTF8(secret), UTF8("et_" + payloadB64)))
 * </pre>
 *
 * - 서명을 먼저 본다. 서명이 맞기 전의 페이로드는 해석하지 않는다.
 * - 비교는 상수 시간(MessageDigest.isEqual). 이전 키를 받는 동안에는 맞은 키에서 멈추지 않고 모두 계산한다 —
 *   걸린 시간으로 어느 키인지 드러나지 않게.
 * - 실패 사유를 나누지 않는다. 어느 칸이 틀렸는지 알려 주면 맞추는 데 쓰인다.
 * - 발급 코드는 두지 않는다. preorder 는 검증만 한다.
 */
@Component
public class AdmissionTicketVerifier {

    static final String PREFIX = "et_";

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_SECRET_LENGTH = 16;
    private static final int MAX_PREVIOUS = 2;
    private static final char SEPARATOR = '.';
    private static final String FIELD = String.valueOf((char) 0x1f);
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final List<byte[]> previous;
    /**
     * 이전 키를 여기까지만 받는다. 이전 키로 낸 마지막 입장권(교체 배포 끝에 발급)은 만료가 교체 끝 + ttl 을 넘지 않고,
     * 만료 판정이 clockSkew 만큼 더 받아 주므로 교체 끝 + ttl + clockSkew 까지 유효할 수 있다.
     * window 와 clockSkew 중 큰 쪽을 더한다(게이트웨이 기준 ttl + window 보다 일찍 닫지 않게).
     */
    private final Instant acceptPreviousUntil;
    private final long clockSkewSeconds;
    private final Clock clock;

    public AdmissionTicketVerifier(AdmissionTicketProperties properties, Clock clock) {
        validate(properties);
        this.secret = properties.secret().getBytes(StandardCharsets.UTF_8);
        this.previous = properties.previous().stream().map(key -> key.getBytes(StandardCharsets.UTF_8)).toList();
        this.acceptPreviousUntil = properties.rolloutEndsAt() == null ? null
                : properties.rolloutEndsAt().plus(properties.ttl())
                        .plus(max(properties.window(), properties.clockSkew()));
        this.clockSkewSeconds = properties.clockSkew().toSeconds();
        this.clock = clock;
    }

    /**
     * 이 상품 · 이 회원에게 게이트웨이가 낸 입장권인가. 만료는 거절하지 않고 칸으로 알린다 —
     * 만료 뒤의 같은 접수 키 재전송에도 기존 예약을 돌려줘야 해서다.
     *
     * @return 통과하면 입장권(ID · 만료 여부). 서명 · 상품 · 회원 · 모양이 하나라도 어긋나면 비어 있다
     */
    public Optional<AdmissionTicket> verify(String token, Long productId, Long customerId) {
        if (token == null || !token.startsWith(PREFIX)) {
            return Optional.empty();
        }
        int mark = token.indexOf(SEPARATOR);
        if (mark < 0) {
            return Optional.empty();
        }
        String payload = token.substring(PREFIX.length(), mark);
        Instant now = clock.instant();
        if (!signatureMatches(payload, decode(token.substring(mark + 1)), now)) {
            return Optional.empty();
        }
        // 서명이 맞으므로 여기서부터는 게이트웨이가 만든 문자열이다. 그래도 모양은 본다.
        byte[] claims = decode(payload);
        if (claims == null) {
            return Optional.empty();
        }
        String[] parts = new String(claims, StandardCharsets.UTF_8).split(FIELD, -1);
        if (parts.length != 3
                || !parts[0].equals(String.valueOf(productId))
                || !parts[1].equals(String.valueOf(customerId))) {
            return Optional.empty();
        }
        OptionalLong expiresAt = epochSecond(parts[2]);
        if (expiresAt.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AdmissionTicket(sha256Hex(token), isExpired(expiresAt.getAsLong(), now)));
    }

    private boolean signatureMatches(String payload, byte[] presented, Instant now) {
        if (presented == null) {
            return false;
        }
        boolean matched = MessageDigest.isEqual(sign(secret, payload), presented);
        if (acceptPreviousUntil == null || !now.isBefore(acceptPreviousUntil)) {
            return matched;
        }
        boolean byPrevious = false;
        for (byte[] key : previous) {
            byPrevious |= MessageDigest.isEqual(sign(key, payload), presented);
        }
        return matched || byPrevious;
    }

    private OptionalLong epochSecond(String exp) {
        try {
            return OptionalLong.of(Long.parseLong(exp));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /** 만료 시각 그 순간은 지난 것이다. 서버 시각 오차만큼만 더 받아 준다. */
    private boolean isExpired(long expiresAt, Instant now) {
        return expiresAt + clockSkewSeconds <= now.getEpochSecond();
    }

    private Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    /** Mac 은 스레드 안전하지 않아 호출마다 만든다. */
    private byte[] sign(byte[] key, String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal((PREFIX + payload).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("입장권 서명 계산 실패", e);
        }
    }

    private byte[] decode(String value) {
        try {
            return DECODER.decode(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String sha256Hex(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 없음", e);
        }
    }

    /** 약한 키로 조용히 돌지 않는다. 설정이 틀리면 기동을 막는다(waiting 과 같은 규칙). */
    private void validate(AdmissionTicketProperties properties) {
        String current = properties.secret();
        if (current == null || current.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException("입장권 비밀키는 %d자 이상이어야 한다".formatted(MIN_SECRET_LENGTH));
        }
        List<String> previous = properties.previous();
        for (String key : previous) {
            if (key.length() < MIN_SECRET_LENGTH) {
                throw new IllegalArgumentException("이전 키도 %d자 이상이어야 한다".formatted(MIN_SECRET_LENGTH));
            }
            if (key.equals(current)) {
                throw new IllegalArgumentException("현재 키를 이전 키로 또 적을 수 없다");
            }
        }
        if (Set.copyOf(previous).size() != previous.size()) {
            throw new IllegalArgumentException("이전 키가 중복이다");
        }
        if (previous.size() > MAX_PREVIOUS) {
            throw new IllegalArgumentException("이전 키는 %d개까지다: %d개".formatted(MAX_PREVIOUS, previous.size()));
        }
        if (!previous.isEmpty() && properties.rolloutEndsAt() == null) {
            throw new IllegalArgumentException("이전 키를 받으려면 교체 배포가 끝나는 시각을 적어야 한다");
        }
        if (properties.ttl().isNegative() || properties.ttl().isZero()
                || properties.window().isNegative() || properties.window().isZero()
                || properties.window().compareTo(properties.ttl()) > 0) {
            throw new IllegalArgumentException("수명 · 창은 양수이고 창은 수명보다 길 수 없다");
        }
    }
}
