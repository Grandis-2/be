package com.grandis.nova.waitingroom.control;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * liveness — 제어 루프가 돌고 있는가. 박동 간격은 길어야 주기의 네 배(시한 + 다음 주기)라 이만큼 멈췄으면 루프가 죽은 것이다.
 * Redis 를 보지 않는다 — 공용 장애로 전 태스크가 함께 재시작되지 않게. 제어 평면이 돌지 않는 구성에서는 늘 UP 이다.
 */
@Component("controlLoopHealthIndicator")
class ControlLoopHealthIndicator implements HealthIndicator {

    static final Duration STALL = Duration.ofSeconds(30);

    private final LoopBeats beats;

    ControlLoopHealthIndicator(LoopBeats beats) {
        this.beats = beats;
    }

    @Override
    public Health health() {
        if (!beats.running()) {
            return Health.up().build();
        }
        return beats.sinceOldest().compareTo(STALL) < 0 ? Health.up().build() : Health.down().build();
    }
}
