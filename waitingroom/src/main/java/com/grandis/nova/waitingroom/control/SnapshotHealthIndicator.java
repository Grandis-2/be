package com.grandis.nova.waitingroom.control;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 판정 재료를 한 번이라도 받았는가. readiness 에 넣어 재료 없이 트래픽을 받지 않게 한다(제어 평면을 끄면 계속 DOWN).
 * 받은 뒤 낡거나 Redis 가 끊기는 것은 내리지 않는다 — 요청이 줄 세우기 · 503 으로 답한다.
 */
@Component("snapshotHealthIndicator")
class SnapshotHealthIndicator implements HealthIndicator {

    private final SnapshotHolder holder;

    SnapshotHealthIndicator(SnapshotHolder holder) {
        this.holder = holder;
    }

    @Override
    public Health health() {
        return holder.current().isPresent() ? Health.up().build() : Health.down().build();
    }
}
