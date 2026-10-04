package com.grandis.nova.waitingroom.control;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 관측 브레이크. 최근 window 동안 전달한 접수가 minSamples 이상이고 나쁜 비율이 badRatio 이상이면 속도에 cut 을 곱한다
 * (floor 아래로는 안 내리고, 한 번 줄이면 hold 동안 더 줄이지 않는다). recoverRatio 미만이 recoverAfter 이어지면
 * stepEvery 마다 step 씩 운영값까지 되돌린다. 기본값은 부하 시험으로 다시 정한다.
 *
 * @param slow 이보다 오래 걸린 전달은 나쁜 응답으로 센다
 */
@ConfigurationProperties("waitingroom.brake")
public record BrakeProperties(Boolean enabled, Duration window, Integer minSamples, Double badRatio, Double recoverRatio,
                              Duration slow, Double cut, Double floor, Duration hold, Duration recoverAfter,
                              Double step, Duration stepEvery) {

    /** 배율은 천분율로 맞추므로 그보다 작은 바닥 · 회복 폭은 0 이 되어 버린다. */
    static final double MIN_FACTOR = 0.001;

    public BrakeProperties {
        enabled = enabled == null || enabled;
        window = window == null ? Duration.ofSeconds(5) : window;
        minSamples = minSamples == null ? 20 : minSamples;
        badRatio = badRatio == null ? 0.2 : badRatio;
        recoverRatio = recoverRatio == null ? 0.05 : recoverRatio;
        slow = slow == null ? Duration.ofSeconds(2) : slow;
        cut = cut == null ? 0.5 : cut;
        floor = floor == null ? 0.1 : floor;
        hold = hold == null ? Duration.ofSeconds(5) : hold;
        recoverAfter = recoverAfter == null ? Duration.ofSeconds(10) : recoverAfter;
        step = step == null ? 0.1 : step;
        stepEvery = stepEvery == null ? Duration.ofSeconds(5) : stepEvery;
        if (window.toSeconds() < 1 || minSamples < 1 || !(badRatio > 0 && badRatio <= 1)
                || !(recoverRatio >= 0 && recoverRatio < badRatio) || !(cut > 0 && cut < 1)
                || !(floor >= MIN_FACTOR && floor <= 1) || !(step >= MIN_FACTOR && step <= 1) || !slow.isPositive()
                || hold.isNegative() || recoverAfter.isNegative() || !stepEvery.isPositive()) {
            throw new IllegalArgumentException("waitingroom.brake 설정이 어긋났다: 0 < cut < 1, 0.001 <= floor · step <= 1, "
                    + "0 <= recover-ratio < bad-ratio <= 1, window >= 1s");
        }
    }
}
