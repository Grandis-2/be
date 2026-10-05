package com.grandis.nova.waitingroom.control;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 시각으로 보정한 시계. 스냅샷을 읽을 때마다 차이를 배우고, 요청 경로는 Redis 를 치지 않고 이 시계를 쓴다 —
 * 노드 시계가 어긋나도 오픈 · 마감을 모든 노드가 같은 순간에 판정한다.
 */
@Component
public class RedisClock {

    /** 이만큼 지나면 왕복이 길어도 새 측정으로 바꾼다 — 가장 짧은 측정만 붙들고 있으면 시계가 흘러가도 모른다. */
    static final long RESAMPLE_AFTER_MILLIS = 30_000;

    private final Clock clock;
    private final AtomicLong offsetMillis = new AtomicLong();
    private long bestRoundTrip = Long.MAX_VALUE;
    private long bestAt;

    public RedisClock(Clock clock) {
        this.clock = clock;
    }

    /** 요청을 보낸 시각과 받은 시각의 가운데를 Redis 가 잰 시각으로 본다. 왕복이 짧은 측정일수록 정확해 그것을 쓴다. */
    public synchronized void learn(long redisNowMillis, long sentAtMillis, long receivedAtMillis) {
        long roundTrip = Math.max(0, receivedAtMillis - sentAtMillis);
        if (roundTrip <= bestRoundTrip || receivedAtMillis - bestAt > RESAMPLE_AFTER_MILLIS) {
            bestRoundTrip = roundTrip;
            bestAt = receivedAtMillis;
            offsetMillis.set(redisNowMillis - (sentAtMillis + receivedAtMillis) / 2);
        }
    }

    public Instant now() {
        return Instant.ofEpochMilli(clock.millis() + offsetMillis.get());
    }

    public long localMillis() {
        return clock.millis();
    }
}
