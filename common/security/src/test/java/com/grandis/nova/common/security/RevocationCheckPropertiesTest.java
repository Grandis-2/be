package com.grandis.nova.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

/** 빈 목록이 "전부 fail-open" 이 되지 않는다. */
@DisplayName("RevocationCheckProperties — 기본 목록")
class RevocationCheckPropertiesTest {

    @Test
    @DisplayName("null 도 빈 목록도 기본 셋(관리자 · 재발급 · 내 정보)으로 대체된다 — 도메인 경로(취소 · 결제 · 배송지)는 공통 기본에 없다")
    void nullAndEmptyFallBackToDefaults() {
        assertThat(new RevocationCheckProperties(null).failClosedPaths()).isEqualTo(RevocationCheckProperties.DEFAULT_FAIL_CLOSED_PATHS);
        assertThat(new RevocationCheckProperties(List.of()).failClosedPaths()).isEqualTo(RevocationCheckProperties.DEFAULT_FAIL_CLOSED_PATHS);
        assertThat(RevocationCheckProperties.DEFAULT_FAIL_CLOSED_PATHS)
                .containsExactly("/api/v1/admin/**", "/api/v1/session/refresh", "/api/v1/me/**")
                .noneMatch(entry -> entry.contains("/orders/") || entry.contains("/preorders/") || entry.contains("/reservations/"));
    }

    @Test
    @DisplayName("항목은 \"/path\" 또는 \"METHOD /path\" — 메서드가 있으면 그것만, 없으면 전부")
    void entriesParseMethodPrefix() {
        assertThat(RevocationCheckProperties.Entry.parse("/api/v1/me/**"))
                .isEqualTo(new RevocationCheckProperties.Entry(Optional.empty(), "/api/v1/me/**"));
        assertThat(RevocationCheckProperties.Entry.parse("  POST   /api/v1/preorders/*/cancel "))
                .isEqualTo(new RevocationCheckProperties.Entry(Optional.of(HttpMethod.POST), "/api/v1/preorders/*/cancel"));
    }

    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {"PSOT /api/v1/x", "post /api/v1/x", "POST", "POST api/v1/x", "/api/v1/x extra", "POST /api/v1/x extra", "", "api/v1/x"})
    @DisplayName("모양이 틀린 항목은 기동 실패 — 오타가 닫는 경로를 조용히 줄이지 않는다")
    void malformedEntryFailsFast(String entry) {
        assertThatThrownBy(() -> new RevocationCheckProperties(List.of("/api/v1/me/**", entry)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(entry);
    }

    @Test
    @DisplayName("명시한 목록은 그대로 쓴다")
    void explicitListIsKept() {
        assertThat(new RevocationCheckProperties(List.of("/api/v1/x/**")).failClosedPaths()).containsExactly("/api/v1/x/**");
    }
}
