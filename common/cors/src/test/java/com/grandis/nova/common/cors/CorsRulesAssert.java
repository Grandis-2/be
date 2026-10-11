package com.grandis.nova.common.cors;

import org.springframework.web.cors.CorsConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/** 두 스택의 소스가 같은 규칙을 내는지 보는 공용 단언. */
final class CorsRulesAssert {

    static final String ORIGINS = "nova.cors.allowed-origins=https://grandis-nova.link";
    static final String SOURCE_BEAN = CorsAutoConfiguration.SOURCE_BEAN;

    private CorsRulesAssert() {
    }

    static void assertRules(CorsConfiguration configuration) {
        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).containsExactly("https://grandis-nova.link");
        assertThat(configuration.getAllowCredentials()).isTrue();
        assertThat(configuration.getAllowedMethods()).containsExactly("GET", "POST", "PUT", "PATCH", "DELETE");
        assertThat(configuration.getAllowedHeaders()).containsExactly("Authorization", "Content-Type", "Idempotency-Key",
                "X-Admission-Ticket", "Queue-Token", "X-Request-Id");
        assertThat(configuration.getExposedHeaders()).containsExactly("Location", "Retry-After", "X-Idempotent-Replay", "X-Request-Id");
        assertThat(configuration.getMaxAge()).isEqualTo(3600L);
    }
}
