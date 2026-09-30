package com.grandis.nova.catalog.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class JpaAuditingConfigTest {

    // Linux JVM 시계처럼 나노초가 있는 시각. 반올림하면 .000002 가 되어 DB 와 어긋난다 — 내려야 한다.
    @Test
    @DisplayName("저장 해상도 시계는 마이크로초 아래를 반올림이 아니라 버림으로 없앤다")
    void storageClockDropsSubMicrosecondPartByFlooring() {
        Clock nanos = Clock.fixed(Instant.parse("2026-01-01T00:00:00.000001900Z"), ZoneOffset.UTC);

        assertThat(JpaAuditingConfig.atStorageResolution(nanos).instant())
                .isEqualTo(Instant.parse("2026-01-01T00:00:00.000001Z"));
    }

    // 초 경계: 반올림이면 다음 초로 넘어간다
    @Test
    @DisplayName("초 경계 직전의 나노초도 다음 초로 넘기지 않는다")
    void storageClockNeverCrossesSecondBoundary() {
        Clock nanos = Clock.fixed(Instant.parse("2026-01-01T00:00:00.999999600Z"), ZoneOffset.UTC);

        assertThat(JpaAuditingConfig.atStorageResolution(nanos).instant())
                .isEqualTo(Instant.parse("2026-01-01T00:00:00.999999Z"));
    }
}
