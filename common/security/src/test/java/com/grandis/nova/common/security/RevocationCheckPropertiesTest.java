package com.grandis.nova.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 빈 목록이 "전부 fail-open" 이 되지 않는다. */
@DisplayName("RevocationCheckProperties — 기본 목록")
class RevocationCheckPropertiesTest {

    @Test
    @DisplayName("null 도 빈 목록도 기본 여섯 개로 대체된다. /api/v1/me/** 가 들어 있다")
    void nullAndEmptyFallBackToDefaults() {
        assertThat(new RevocationCheckProperties(null).failClosedPaths()).isEqualTo(RevocationCheckProperties.DEFAULT_FAIL_CLOSED_PATHS);
        assertThat(new RevocationCheckProperties(List.of()).failClosedPaths()).isEqualTo(RevocationCheckProperties.DEFAULT_FAIL_CLOSED_PATHS);
        assertThat(RevocationCheckProperties.DEFAULT_FAIL_CLOSED_PATHS).hasSize(6).contains("/api/v1/me/**", "/api/v1/session/refresh");
    }

    @Test
    @DisplayName("명시한 목록은 그대로 쓴다")
    void explicitListIsKept() {
        assertThat(new RevocationCheckProperties(List.of("/api/v1/x/**")).failClosedPaths()).containsExactly("/api/v1/x/**");
    }
}
