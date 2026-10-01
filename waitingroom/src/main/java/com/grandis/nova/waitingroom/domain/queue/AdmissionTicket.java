package com.grandis.nova.waitingroom.domain.queue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 입장권 — 차례가 왔다는 증거. preorder 가 같은 형식으로 다시 검증하고 1회만 소비한다.
 * 수명이 짧아야 한다(옵션을 고르고 신청할 시간만). 길면 받아 두고 나중에 몰려와 상한을 넘긴다.
 */
public final class AdmissionTicket {

    public static final long TTL_SEC = 120;
    /** 발급 값을 끊는 단위. 최소 수명은 TTL_SEC - WINDOW_SEC 이다. */
    public static final long WINDOW_SEC = 30;
    static final String PREFIX = "et_";

    private final SignedToken signer;

    private AdmissionTicket(SignedToken signer) {
        this.signer = signer;
    }

    public static AdmissionTicket of(String secret, List<String> previous, Instant rolloutEndsAt) {
        return new AdmissionTicket(SignedToken.of(PREFIX, TTL_SEC, WINDOW_SEC, secret, previous, rolloutEndsAt));
    }

    public String issue(String productKey, String customerId, Instant now) {
        return signer.issue(productKey, customerId, now);
    }

    /** @return 회원 식별자. 하나라도 어긋나면 빈 값 */
    public Optional<String> verify(String ticket, String productKey, Instant now) {
        return signer.verify(ticket, productKey, now);
    }

    public long acceptedByPrevious() {
        return signer.acceptedByPrevious();
    }
}
