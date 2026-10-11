package com.grandis.nova.common.cors;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 기동으로 본다 — 기본값은 spring.factories 로 등록된 EnvironmentPostProcessor 가 깐다. */
class CorsDefaultsTest {

    @Test
    void 설정이_없으면_운영_프론트_두_도메인과_API_도메인을_허용한다() {
        try (ConfigurableApplicationContext context = run(Map.of())) {
            assertThat(context.getBean(CorsProperties.class).allowedOrigins()).containsExactly(
                    "https://grandis-nova.link", "https://www.grandis-nova.link", "https://api.grandis-nova.link");
        }
    }

    @Test
    void 설정에_목록을_적으면_기본값과_섞이지_않고_통째로_바뀐다() {
        try (ConfigurableApplicationContext context = run(Map.of(
                "nova.cors.allowed-origins[0]", "http://localhost:5173"))) {
            assertThat(context.getBean(CorsProperties.class).allowedOrigins()).containsExactly("http://localhost:5173");
        }
    }

    @Test
    void 한_줄에_콤마로_이어_써도_목록이_된다_환경변수와_같은_모양() {
        try (ConfigurableApplicationContext context = run(Map.of(
                "nova.cors.allowed-origins", "http://localhost:5173,http://localhost:9000"))) {
            assertThat(context.getBean(CorsProperties.class).allowedOrigins())
                    .containsExactly("http://localhost:5173", "http://localhost:9000");
        }
    }

    @Test
    void 설정의_오리진_모양이_틀리면_기동이_실패한다() {
        assertThatThrownBy(() -> run(Map.of("nova.cors.allowed-origins[0]", "https://grandis-nova.link/")))
                .rootCause().hasMessageContaining("nova.cors.allowed-origins");
    }

    private static ConfigurableApplicationContext run(Map<String, Object> properties) {
        SpringApplication application = new SpringApplication(App.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        String[] args = properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
        return application.run(args);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class App {
    }
}
