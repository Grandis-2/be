package com.grandis.nova.waitingroom.domain.queue;

/** 예상 대기 시간과 표시 구간. 배수율은 리더가 이 모델에 배분한 초당 입장 인원이다(운영자가 정한 속도라 튀지 않는다). */
public final class EtaPolicy {

    /** 배수율을 모른다. 음수를 쓰지 않는다 — 어떤 구간보다 작아 "곧 입장" 으로 읽힌다. */
    public static final double UNKNOWN = Double.NaN;

    private static final double[] BUCKET_EDGES = {30, 90, 450};
    private static final EtaDisplay[] BUCKETS = {
            EtaDisplay.ALMOST_THERE, EtaDisplay.ABOUT_A_MINUTE, EtaDisplay.ABOUT_FIVE_MINUTES, EtaDisplay.LONG_WAIT
    };

    private EtaPolicy() {
    }

    /** 앞선 rank 명이 빠지는 데 걸리는 시간(초). */
    public static double etaSec(long rank, double credit) {
        if (rank < 0) {
            throw new IllegalArgumentException("rank 는 0 이상이어야 한다: " + rank);
        }
        if (rank == 0) {
            return 0;
        }
        return credit > 0 ? rank / credit : UNKNOWN;
    }

    /** 응답에 실을 초. 모름(NaN · 무한 · 음수)을 0 으로 말하지 않고 가장 넓은 구간의 하한을 준다. */
    public static long reportSec(double etaSec) {
        if (!Double.isFinite(etaSec) || etaSec < 0) {
            return (long) BUCKET_EDGES[BUCKET_EDGES.length - 1];
        }
        return (long) etaSec;
    }

    /** 표시할 구간. 모르면 가장 넓은 구간이다 — 짧게 말했다가 오래 기다리게 하는 쪽이 훨씬 나쁘다. */
    public static EtaDisplay bucket(double etaSec) {
        if (!(etaSec >= 0)) {
            return BUCKETS[BUCKETS.length - 1];
        }
        for (int i = 0; i < BUCKET_EDGES.length; i++) {
            if (etaSec < BUCKET_EDGES[i]) {
                return BUCKETS[i];
            }
        }
        return BUCKETS[BUCKETS.length - 1];
    }
}
