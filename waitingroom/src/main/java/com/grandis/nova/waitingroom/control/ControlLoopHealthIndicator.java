package com.grandis.nova.waitingroom.control;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * liveness — 제어 루프가 돌고 있는가. 한 바퀴는 시한(주기의 세 배) 안에 끝나므로 이만큼 멈췄으면 스케줄러가 죽은 것이다.
 * Redis 를 보지 않는다 — 공용 장애로 전 태스크가 함께 재시작되지 않게. 제어 평면을 끈 구성에서는 늘 UP 이다.
 */
@Component("controlLoopHealthIndicator")
class ControlLoopHealthIndicator implements HealthIndicator {

    static final Duration STALL = Duration.ofSeconds(30);

    private final ObjectProvider<ControlPlane> controlPlane;

    ControlLoopHealthIndicator(ObjectProvider<ControlPlane> controlPlane) {
        this.controlPlane = controlPlane;
    }

    @Override
    public Health health() {
        ControlPlane plane = controlPlane.getIfAvailable();
        if (plane == null || !plane.isRunning()) {
            return Health.up().build();
        }
        return plane.sinceLastBeat().compareTo(STALL) < 0 ? Health.up().build() : Health.down().build();
    }
}
