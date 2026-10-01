package com.grandis.nova.waitingroom.domain.queue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 대기 토큰 — 줄에서 내 자리를 조회하는 수단. 회원에 묶여 있어 남의 순서를 볼 수 없다.
 * 게이트웨이 안에서만 쓰고 preorder 로 넘기지 않는다. 입장권과 같은 비밀을 쓰고 접두로 쓰임을 가른다.
 */
public final class QueueToken {

    public static final long TTL_SEC = 3_600;
    public static final long WINDOW_SEC = 600;
    static final String PREFIX = "qt_";

    private final SignedToken signer;

    private QueueToken(SignedToken signer) {
        this.signer = signer;
    }

    /** 옛 키를 검증에서도 받는다 — 대기 토큰을 거절당하면 자리를 잃고 처음부터 다시 선다. */
    public static QueueToken of(String secret, List<String> previous, Instant rolloutEndsAt) {
        return new QueueToken(SignedToken.of(PREFIX, TTL_SEC, WINDOW_SEC, secret, previous, rolloutEndsAt));
    }

    public String issue(String productKey, String customerId, Instant now) {
        return signer.issue(productKey, customerId, now);
    }

    /** @return 회원 식별자. 하나라도 어긋나면 빈 값 */
    public Optional<String> verify(String token, String productKey, Instant now) {
        return signer.verify(token, productKey, now);
    }

    public long acceptedByPrevious() {
        return signer.acceptedByPrevious();
    }
}
