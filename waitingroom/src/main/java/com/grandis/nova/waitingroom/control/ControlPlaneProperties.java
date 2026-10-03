package com.grandis.nova.waitingroom.control;

import com.grandis.nova.waitingroom.domain.queue.PollIntervalPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * 제어 평면 주기와 수명. 서로의 관계(리스 > 연장 간격, 낡음 > 갱신 간격 등)를 생성자가 지킨다 —
 * 어긋나면 리더가 둘이 되거나 멀쩡한 스냅샷을 낡았다고 본다. 배분 회차는 1초 고정이다(몫이 초당 값이라).
 */
@ConfigurationProperties("waitingroom.control")
public record ControlPlaneProperties(
        Duration leaderLease,
        Duration leaderRenew,
        Duration snapshotRefresh,
        Duration snapshotStaleAfter,
        Duration gatewayReapAfter,
        Duration closeGrace,
        Integer sweepScanLimit,
        Integer sweepBudget
) {

    public static final Duration TICK = Duration.ofSeconds(1);

    public ControlPlaneProperties {
        leaderLease = leaderLease == null ? Duration.ofSeconds(2) : leaderLease;
        leaderRenew = leaderRenew == null ? Duration.ofMillis(500) : leaderRenew;
        snapshotRefresh = snapshotRefresh == null ? Duration.ofMillis(500) : snapshotRefresh;
        snapshotStaleAfter = snapshotStaleAfter == null ? Duration.ofSeconds(5) : snapshotStaleAfter;
        gatewayReapAfter = gatewayReapAfter == null ? Duration.ofSeconds(5) : gatewayReapAfter;
        // 마감 뒤 줄을 지우기까지의 유예. 생존 신호 수명보다 길어야 입장하고 조회를 놓친 사람을 청소가 먼저 '입장 미통지'로 옮긴다
        closeGrace = closeGrace == null ? Duration.ofSeconds(300) : closeGrace;
        sweepScanLimit = sweepScanLimit == null ? 1_000 : sweepScanLimit;
        sweepBudget = sweepBudget == null ? 1_000 : sweepBudget;
        // 연장이 한 번 늦어도 리스 안에 다음 연장이 오고, 믿는 기간(리스 − 연장 간격)이 남게
        if (leaderRenew.multipliedBy(2).compareTo(leaderLease) >= 0) {
            throw new IllegalArgumentException("leader-renew 의 두 배는 leader-lease 보다 짧아야 한다");
        }
        if (snapshotStaleAfter.compareTo(TICK.plus(snapshotRefresh)) <= 0) {
            throw new IllegalArgumentException("snapshot-stale-after 는 1초 + snapshot-refresh 보다 길어야 한다");
        }
        if (gatewayReapAfter.compareTo(TICK.multipliedBy(2)) <= 0) {
            throw new IllegalArgumentException("gateway-reap-after 는 2초보다 길어야 한다");
        }
        // 한 바퀴는 시한(주기의 세 배) 뒤 다음 주기에 다시 돈다 — 박동 간격이 liveness 시한을 넘으면 멀쩡한 태스크가 재시작된다
        Duration slowest = Collections.max(List.of(TICK, leaderRenew, snapshotRefresh));
        if (slowest.multipliedBy(4).compareTo(ControlLoopHealthIndicator.STALL) >= 0) {
            throw new IllegalArgumentException("가장 긴 주기의 네 배가 " + ControlLoopHealthIndicator.STALL + " 보다 짧아야 한다");
        }
        if (closeGrace.compareTo(PollIntervalPolicy.aliveTtl()) <= 0) {
            throw new IllegalArgumentException("close-grace 는 생존 신호 수명(" + PollIntervalPolicy.aliveTtl() + ")보다 길어야 한다");
        }
        if (sweepScanLimit < 1 || sweepScanLimit > 3_999 || sweepBudget < 1 || sweepBudget > 7_999) {
            throw new IllegalArgumentException("sweep-scan-limit 은 1..3999, sweep-budget 은 1..7999 여야 한다");
        }
    }

    /** 연장 요청 뒤 리더라고 믿는 기간. 리스에서 연장 간격만큼 여유를 뺀다. */
    public Duration trustedLease() {
        return leaderLease.minus(leaderRenew);
    }

    /** 리더 울타리 표의 수명. 리스보다 길어야 옛 리더가 표가 사라진 틈에 끼어들지 못한다. */
    public Duration fenceTtl() {
        return leaderLease.multipliedBy(30);
    }
}
