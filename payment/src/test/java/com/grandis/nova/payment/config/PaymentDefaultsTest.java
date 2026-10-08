package com.grandis.nova.payment.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 관리 포트 · 헬스 기본값은 설정이 없으면 쓰이고, 관리 포트를 서비스 포트와 같게 두거나 끄면 기동을 막는다. */
class PaymentDefaultsTest {

    @Test
    void 설정에_없으면_관리_포트와_readiness_기본값을_쓰고_설정이_주면_그_값이_이긴다() {
        StandardEnvironment empty = apply(new StandardEnvironment());
        assertThat(empty.getProperty("management.server.port")).isEqualTo("9086");
        assertThat(empty.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
        assertThat(empty.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
        assertThat(empty.getProperty("management.endpoint.health.group.readiness.include")).isEqualTo("readinessState");

        assertThat(apply(with(Map.of("management.server.port", "9999"))).getProperty("management.server.port"))
                .isEqualTo("9999");
    }

    @Test
    void 관리_포트를_서비스_포트와_같게_두거나_끄면_기동을_막고_웹_없는_기동은_막지_않는다() {
        assertThatThrownBy(() -> apply(with(Map.of("server.port", "8085", "management.server.port", "8085"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SAME");
        assertThatThrownBy(() -> apply(with(Map.of("management.server.port", "-1"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DISABLED");
        assertThat(apply(with(Map.of("management.server.port", "-1", "spring.main.web-application-type", "none")))
                .getProperty("management.server.port")).isEqualTo("-1");
    }

    private static StandardEnvironment with(Map<String, String> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new MapPropertySource("application.yml", Map.<String, Object>copyOf(properties)));
        return environment;
    }

    private static StandardEnvironment apply(StandardEnvironment environment) {
        new PaymentDefaults().postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }
}
