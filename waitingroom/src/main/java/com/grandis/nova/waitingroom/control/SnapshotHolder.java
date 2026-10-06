package com.grandis.nova.waitingroom.control;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 요청 경로가 읽는 판정 재료. 잠금 없이 통째로 갈아 끼운다. 낡음은 Redis 시각으로 잰 발행 뒤 나이로 판정한다 —
 * 낡은 재료로는 줄 없는 통과를 내지 않는다(판정기가 줄로 보낸다).
 */
@Component
public class SnapshotHolder {

    private final AtomicReference<GatewaySnapshot> current = new AtomicReference<>();
    private final RedisClock redisClock;
    private final ControlPlaneProperties properties;

    SnapshotHolder(RedisClock redisClock, ControlPlaneProperties properties) {
        this.redisClock = redisClock;
        this.properties = properties;
    }

    /**
     * 더 새 재료만 받는다. 다만 가진 것이 낡았으면 시각이 더 이른 재료도 받는다 — Redis 장애 조치로 시계가 뒤인
     * 복제본이 올라오면 새 재료의 시각이 작아지는데, 계속 버리면 낡은 채로 머문다.
     *
     * @return 받았으면 true
     */
    boolean replace(GatewaySnapshot snapshot) {
        GatewaySnapshot previous = current.get();
        if (previous != null && snapshot.publishedAtMillis() < previous.publishedAtMillis() && !stale()) {
            return false;
        }
        return current.compareAndSet(previous, snapshot);
    }

    /** 아직 한 번도 못 받았으면 빈 값. */
    public Optional<GatewaySnapshot> current() {
        return Optional.ofNullable(current.get());
    }

    /** 발행 뒤 나이(ms). 아직 없으면 -1. */
    public long ageMillis() {
        GatewaySnapshot snapshot = current.get();
        return snapshot == null ? -1 : redisClock.now().toEpochMilli() - snapshot.publishedAtMillis();
    }

    /** 리더가 멈췄거나 이 노드가 못 받는 중이면 참. 처음 받기 전도 낡은 것이다. */
    public boolean stale() {
        GatewaySnapshot snapshot = current.get();
        if (snapshot == null) {
            return true;
        }
        Instant publishedAt = Instant.ofEpochMilli(snapshot.publishedAtMillis());
        return redisClock.now().isAfter(publishedAt.plus(properties.snapshotStaleAfter()));
    }
}
