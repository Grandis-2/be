package com.grandis.nova.common.cors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorsPropertiesTest {

    @Test
    void 정확한_오리진은_받는다() {
        CorsProperties properties = new CorsProperties(List.of("https://grandis-nova.link", "http://localhost:5173"));

        assertThat(properties.allowedOrigins()).containsExactly("https://grandis-nova.link", "http://localhost:5173");
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.grandis-nova.link", "https://grandis-nova.link/", "https://grandis-nova.link/app",
            "grandis-nova.link", "ftp://grandis-nova.link", " https://grandis-nova.link", ""})
    void 와일드카드_경로_끝_슬래시_스킴_없음은_기동_실패다(String origin) {
        assertThatThrownBy(() -> new CorsProperties(List.of("https://www.grandis-nova.link", origin)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nova.cors.allowed-origins");
    }

    @Test
    void 비어_있으면_기동_실패다() {
        assertThatThrownBy(() -> new CorsProperties(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CorsProperties(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
