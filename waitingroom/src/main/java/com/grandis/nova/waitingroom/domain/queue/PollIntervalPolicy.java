package com.grandis.nova.waitingroom.domain.queue;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * 순서 조회 간격을 서버가 정한다. 부하를 정하는 것은 대기 인원이 아니라 예상 대기 시간이고,
 * 개인은 자기가 얼마나 기다릴지 몰라 클라이언트에 맡길 수 없다.
 */
public final class PollIntervalPolicy {

    /** 예상 대기 시간 밴드 경계(초)와 밴드별 기본 간격(초). */
    private static final double[] BAND_EDGES = {5, 30, 120};
    private static final long[] BAND_INTERVALS = {1, 3, 10, 30};

    static final double STANDARD_JITTER_RATIO = 0.2;
    private static final long MIN_INTERVAL_SEC = 1;
    private static final long MAX_INTERVAL_SEC = 60;
    /** 흔들림 폭의 바닥(초). 비율만 쓰면 1초 밴드는 반올림에 먹혀 안 흩어진다. */
    private static final double MIN_JITTER_SEC = 1.0;
    /** 탭이 뒤로 가 타이머가 밀리는 것까지 몇 번 놓쳐도 줄에 남기는가. */
    private static final long MISSED_POLLS = 3;
    /** 마지막 조회의 왕복과 타이머 드리프트를 덮는 여유(초). */
    private static final long TTL_MARGIN_SEC = 10;

    private final double jitterRatio;

    private PollIntervalPolicy(double jitterRatio) {
        this.jitterRatio = jitterRatio;
    }

    public static PollIntervalPolicy standard() {
        return of(STANDARD_JITTER_RATIO);
    }

    /** 0 이면 흔들지 않는다(시험용). 1 이상이면 아래쪽 끝이 0 이하라 대부분 1초로 몰린다. */
    public static PollIntervalPolicy of(double jitterRatio) {
        if (!(jitterRatio >= 0 && jitterRatio < 1)) {
            throw new IllegalArgumentException("jitterRatio 는 0 이상 1 미만이어야 한다: " + jitterRatio);
        }
        return new PollIntervalPolicy(jitterRatio);
    }

    /**
     * 생존 신호 수명. 간격이 최대(60초)여도 성실히 조회하는 사람이 이탈자로 걷히지 않아야 한다 —
     * 걷힌 뒤 다시 서면 새 순서라 뒤로 밀린다. k 번 놓친 사람은 (k+1) × 간격에 오므로 하나 더 곱한다.
     */
    public static Duration aliveTtl() {
        return maxInterval().multipliedBy(MISSED_POLLS + 1).plusSeconds(TTL_MARGIN_SEC);
    }

    public static Duration maxInterval() {
        return Duration.ofSeconds(MAX_INTERVAL_SEC);
    }

    /** 이 사람의 조회 간격(초). random 은 [0,1] 을 낸다 — 주입받아야 실패를 재현할 수 있다. */
    public long intervalSec(double etaSec, DoubleSupplier random) {
        double base = bandInterval(etaSec);
        // 천장을 흔들림 위쪽 끝에 건다 — 흔든 뒤에 자르면 상한 위 값이 한 점에 모여 같은 초에 돌아온다
        double ceiling = jitterRatio == 0 ? MAX_INTERVAL_SEC
                : Math.min(MAX_INTERVAL_SEC / (1 + jitterRatio), MAX_INTERVAL_SEC - MIN_JITTER_SEC);
        double scaled = Math.min(base, ceiling);
        double spread = jitterRatio == 0 ? 0 : Math.max(scaled * jitterRatio, MIN_JITTER_SEC);
        double jittered = scaled + spread * (2 * random.getAsDouble() - 1);
        return Math.clamp(Math.round(jittered), MIN_INTERVAL_SEC, MAX_INTERVAL_SEC);
    }

    /** 예상 대기 시간을 모르면(NaN · 음수) 가장 먼 밴드다 — 모를수록 자주 묻게 하면 안 된다. */
    private static long bandInterval(double etaSec) {
        if (!(etaSec >= 0)) {
            return BAND_INTERVALS[BAND_INTERVALS.length - 1];
        }
        for (int i = 0; i < BAND_EDGES.length; i++) {
            if (etaSec < BAND_EDGES[i]) {
                return BAND_INTERVALS[i];
            }
        }
        return BAND_INTERVALS[BAND_INTERVALS.length - 1];
    }
}
