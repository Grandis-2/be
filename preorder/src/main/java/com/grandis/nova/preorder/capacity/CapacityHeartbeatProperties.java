package com.grandis.nova.preorder.capacity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 살아 있는 태스크와 태스크당 접수 용량을 공유 Redis 에 알린다. 대기열이 전체 입장 속도를 "살아 있는 태스크 용량의 합" 으로 잡는다.
 *
 * @param enabled         끄면 알리지 않는다 — 대기열은 고정 입장 속도로 돌아간다
 * @param interval        알리는 주기
 * @param staleAfter      마지막으로 알린 뒤 이만큼 지나면 죽은 태스크로 본다. 주기의 세 배 — 한두 번 놓쳐도 빠지지 않게
 * @param acceptPerSecond 태스크 하나가 버티는 초당 접수 수. 로컬 실측 한계(CPU 2 · 풀 10 인스턴스 1대 약 200건/초)의 절반 —
 *                        운영 실측으로 바꾼다
 */
@ConfigurationProperties("nova.capacity.heartbeat")
record CapacityHeartbeatProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("5s") Duration interval,
        @DefaultValue("15s") Duration staleAfter,
        @DefaultValue("100") int acceptPerSecond
) {

    CapacityHeartbeatProperties {
        if (interval.toSeconds() < 1) {
            throw new IllegalArgumentException("nova.capacity.heartbeat.interval 은 1초 이상이어야 한다");
        }
        if (staleAfter.compareTo(interval.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException("nova.capacity.heartbeat.stale-after 는 interval 의 두 배 이상이어야 한다");
        }
        if (acceptPerSecond < 1) {
            throw new IllegalArgumentException("nova.capacity.heartbeat.accept-per-second 는 1 이상이어야 한다");
        }
    }
}
