package com.grandis.nova.waitingroom.domain.product;

import java.time.Duration;

/**
 * 받아 줄 최대 대기 시간. 줄 상한은 입장 속도 × 이 시간이다 — 그 안에 못 들어갈 사람은 세우지 않는다.
 * 기본은 제한 없음이고, 운영자가 정했을 때만 상한이 생긴다.
 */
public record MaxWait(Duration duration) {

    private static final MaxWait UNLIMITED = new MaxWait(null);

    public MaxWait {
        // 초 단위로 곱하므로 1초 미만은 0 이 되어 아무도 줄에 못 선다
        if (duration != null && duration.toSeconds() < 1) {
            throw new IllegalArgumentException("최대 대기 시간은 1초 이상이어야 한다: " + duration);
        }
    }

    public static MaxWait unlimited() {
        return UNLIMITED;
    }

    public static MaxWait of(Duration duration) {
        if (duration == null) {
            throw new IllegalArgumentException("duration 은 필수다. 제한 없음은 unlimited() 를 쓴다");
        }
        return new MaxWait(duration);
    }

    /** 초당 {@code credit} 명이 빠질 때 받아 줄 인원. 곱이 넘치면 제한 없음과 같다. */
    public long capacity(long credit) {
        if (duration == null) {
            return Long.MAX_VALUE;
        }
        try {
            return Math.multiplyExact(credit, duration.toSeconds());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
