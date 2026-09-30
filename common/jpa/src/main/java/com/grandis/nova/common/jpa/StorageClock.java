package com.grandis.nova.common.jpa;

import java.time.Clock;
import java.time.Duration;

/**
 * DB 시각 칼럼의 해상도로 내린 시계.
 *
 * JVM 시계는 OS 에 따라 나노초까지 주는데(Linux) MySQL 은 datetime(6) 에 넣으며 마이크로초 아래를 반올림한다.
 * 그대로 두면 저장하고 돌려준 값과 DB 에 들어간 값이 어긋난다 — 같은 행인데 메모리와 DB 의 시각이 다르고,
 * macOS(마이크로초 시계)에서는 드러나지 않는다. 미리 내려 두면 반올림할 것이 없다.
 */
public final class StorageClock {

    /** DB 시각 칼럼(datetime(6))이 담는 가장 작은 단위. */
    public static final Duration RESOLUTION = Duration.ofNanos(1_000);

    private StorageClock() {
    }

    /** 바탕 시계의 시각을 {@link #RESOLUTION} 단위로 내린다(반올림하지 않는다). */
    public static Clock atStorageResolution(Clock base) {
        return Clock.tick(base, RESOLUTION);
    }
}
