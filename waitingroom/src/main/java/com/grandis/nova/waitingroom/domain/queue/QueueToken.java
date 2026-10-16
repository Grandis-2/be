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
    /** 남은 수명이 이 아래면 조회 응답에 새 토큰을 싣는다. 새 토큰은 최소 50분이 남아 다시 실리지 않는다. */
    public static final long RENEW_BELOW_SEC = TTL_SEC / 2;
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

    /** @return 유효한 토큰의 회원과 만료 시각. 하나라도 어긋나거나 만료됐으면 빈 값 */
    public Optional<SignedToken.Holder> verify(String token, String productKey, Instant now) {
        return signer.authenticate(token, productKey, now).filter(holder -> !holder.expired(now));
    }

    /** 만료가 가까운 토큰이면 바꿔 쓸 새 토큰을 준다. 오래 기다려도 조회가 끊기지 않게 한다. */
    public Optional<String> renewal(String productKey, SignedToken.Holder holder, Instant now) {
        if (!holder.expiresAt().isBefore(now.plusSeconds(RENEW_BELOW_SEC))) {
            return Optional.empty();
        }
        return Optional.of(issue(productKey, holder.customerId(), now));
    }

    public long acceptedByPrevious() {
        return signer.acceptedByPrevious();
    }
}
