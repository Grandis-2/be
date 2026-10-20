package com.grandis.nova.worker.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 포트 · 헬스 기본값은 설정이 없으면 쓰이고, 웹 서버를 끄면 기동을 막는다. */
class WorkerDefaultsTest {

    @Test
    void 설정에_없으면_기본값을_쓰고_설정이_주면_그_값이_이긴다() {
        StandardEnvironment empty = apply(new StandardEnvironment());
        assertThat(empty.getProperty("spring.application.name")).isEqualTo("worker");
        assertThat(empty.getProperty("server.port")).isEqualTo("9087");
        assertThat(empty.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,prometheus");
        assertThat(empty.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
        assertThat(empty.getProperty("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,consumers");
        assertThat(empty.getProperty("management.metrics.distribution.percentiles-histogram.worker.mock.latency"))
                .isEqualTo("true");

        assertThat(apply(with(Map.of("server.port", "9999"))).getProperty("server.port")).isEqualTo("9999");
    }

    @Test
    void 웹_서버를_끄면_기동을_막는다() {
        assertThatThrownBy(() -> apply(with(Map.of("spring.main.web-application-type", "none"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("web-application-type");
    }

    private static StandardEnvironment with(Map<String, String> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new MapPropertySource("application.yml", Map.<String, Object>copyOf(properties)));
        return environment;
    }

    private static StandardEnvironment apply(StandardEnvironment environment) {
        new WorkerDefaults().postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }
}
