package com.grandis.nova.waitingroom.domain.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 입장권 — 차례가 왔다는 증거. preorder 가 같은 형식으로 다시 검증하고 1회만 소비한다.
 * 수명이 짧아야 한다(옵션을 고르고 신청할 시간만). 길면 받아 두고 나중에 몰려와 상한을 넘긴다.
 * 발급 시각(입장 시각)을 실어, preorder 가 수명을 몰라도 마지막 접수 이전에 낸 입장권을 가린다.
 */
public final class AdmissionTicket {

    /** 기본 수명. 누구나 입장 시각부터 정확히 이만큼 받는다. */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(120);
    /** 더 짧으면 옵션을 고르기 전에 만료되고, 더 길면 받아 둔 입장권이 한꺼번에 몰린다. */
    static final Duration MIN_TTL = Duration.ofSeconds(30);
    static final Duration MAX_TTL = Duration.ofMinutes(10);
    static final String PREFIX = "et_";

    private final SignedToken signer;
    private final long ttlSec;

    private AdmissionTicket(SignedToken signer, long ttlSec) {
        this.signer = signer;
        this.ttlSec = ttlSec;
    }

    public static AdmissionTicket of(String secret, List<String> previous, Instant rolloutEndsAt) {
        return of(secret, previous, rolloutEndsAt, DEFAULT_TTL);
    }

    public static AdmissionTicket of(String secret, List<String> previous, Instant rolloutEndsAt, Duration ttl) {
        if (ttl.compareTo(MIN_TTL) < 0 || ttl.compareTo(MAX_TTL) > 0 || ttl.toMillis() % 1_000 != 0) {
            throw new IllegalArgumentException("입장권 수명은 %s 이상 %s 이하의 초 단위여야 한다: %s"
                    .formatted(MIN_TTL, MAX_TTL, ttl));
        }
        return new AdmissionTicket(SignedToken.stamped(PREFIX, ttl.toSeconds(), secret, previous, rolloutEndsAt),
                ttl.toSeconds());
    }

    /** @param admittedAt 입장 시각. 같은 입장이면 같은 입장권이 나온다 */
    public String issue(String productKey, String customerId, Instant admittedAt) {
        return signer.issue(productKey, customerId, admittedAt);
    }

    public long ttlSec() {
        return ttlSec;
    }

    /** @return 회원 식별자. 하나라도 어긋나면 빈 값 */
    public Optional<String> verify(String ticket, String productKey, Instant now) {
        return signer.verify(ticket, productKey, now);
    }

    /** 서명 · 모델이 맞으면 회원과 만료 시각. 만료돼도 같은 접수의 재전송일 수 있어 거절과 나눈다. */
    public Optional<SignedToken.Holder> authenticate(String ticket, String productKey, Instant now) {
        return signer.authenticate(ticket, productKey, now);
    }

    /** 이 시각에 낸 입장권이 만료되는 때. */
    public Instant expiresAt(Instant issuedAt) {
        return signer.expiresAt(issuedAt);
    }

    public long acceptedByPrevious() {
        return signer.acceptedByPrevious();
    }
}
