package com.grandis.nova.waitingroom.control;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * preorder 용량 표시(contracts/preorder-capacity-heartbeat.md)로 전역 입장 속도의 상한을 잡는다.
 * 적용 속도 = min(운영값, 살아 있는 태스크 용량의 합) × 브레이크. 표시가 하나도 없으면 운영값만 쓰고,
 * 표시가 모두 낡았거나 읽지 못하면 직전 합을 그대로 쓴다.
 *
 * @param enabled          끄면 읽지 않고 운영값만 쓴다
 * @param key              preorder 가 적는 해시
 * @param warmup           갓 뜬 태스크로 보는 기간. 계약 예시(1~2분)의 긴 쪽 — JIT · 커넥션 풀 · 캐시가 덜 데워진 태스크에 몰지 않게
 * @param warmupShare      갓 뜬 태스크의 용량에 곱할 비율. 계약 예시(절반)
 * @param preorderMaxTasks preorder 가 늘 수 있는 최대 대수. 관리자 위험선(대수 × 태스크당 용량)에 쓴다 — 인프라 max 와 같게, 지금 5
 */
@ConfigurationProperties("waitingroom.capacity")
public record CapacityProperties(Boolean enabled, String key, Duration warmup, Double warmupShare,
                                 Integer preorderMaxTasks) {

    public CapacityProperties {
        enabled = enabled == null || enabled;
        key = key == null ? "preorder:capacity:tasks" : key;
        warmup = warmup == null ? Duration.ofMinutes(2) : warmup;
        warmupShare = warmupShare == null ? 0.5 : warmupShare;
        preorderMaxTasks = preorderMaxTasks == null ? 5 : preorderMaxTasks;
        if (key.isBlank()) {
            throw new IllegalArgumentException("waitingroom.capacity.key 가 비어 있다");
        }
        if (warmup.isNegative()) {
            throw new IllegalArgumentException("waitingroom.capacity.warmup 은 0 이상이어야 한다");
        }
        if (preorderMaxTasks < 1) {
            throw new IllegalArgumentException("waitingroom.capacity.preorder-max-tasks 는 1 이상이어야 한다: " + preorderMaxTasks);
        }
        if (!(warmupShare > 0 && warmupShare <= 1)) {
            throw new IllegalArgumentException("waitingroom.capacity.warmup-share 는 0 초과 1 이하여야 한다: " + warmupShare);
        }
    }

    /** 모두 기본값. */
    public static CapacityProperties defaults() {
        return new CapacityProperties(null, null, null, null, null);
    }
}
