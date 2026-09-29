package com.grandis.nova.common.jpa;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class StorageClockTest {

    // Linux JVM 시계처럼 나노초가 있는 시각. 반올림하면 .000002 가 되어 DB 와 어긋난다 — 내려야 한다.
    @Test
    void dropsSubMicrosecondPartByFlooring() {
        Clock nanos = Clock.fixed(Instant.parse("2026-01-01T00:00:00.000001900Z"), ZoneOffset.UTC);

        assertThat(StorageClock.atStorageResolution(nanos).instant())
                .isEqualTo(Instant.parse("2026-01-01T00:00:00.000001Z"));
    }

    @Test
    void keepsMicrosecondsAndZone() {
        Clock micros = Clock.fixed(Instant.parse("2026-01-01T00:00:00.123456Z"), ZoneOffset.UTC);

        Clock clock = StorageClock.atStorageResolution(micros);

        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-01-01T00:00:00.123456Z"));
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
