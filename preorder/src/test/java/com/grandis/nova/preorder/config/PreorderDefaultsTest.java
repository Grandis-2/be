package com.grandis.nova.preorder.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 설정 파일에 값이 없으면 코드 기본값을 쓰고, 있으면 설정 파일 값이 이긴다. */
class PreorderDefaultsTest {

    @Test
    void 설정에_없으면_기본값을_쓴다() {
        StandardEnvironment environment = apply(new StandardEnvironment());

        assertThat(environment.getProperty("resilience4j.bulkhead.configs.default.max-concurrent-calls")).isEqualTo("20");
        assertThat(environment.getProperty("resilience4j.bulkhead.instances.order.base-config")).isEqualTo("default");
        assertThat(environment.getProperty("spring.http.serviceclient.catalog.read-timeout")).isEqualTo("1s");
        assertThat(environment.getProperty("spring.http.serviceclient.order.connect-timeout")).isEqualTo("300ms");
        assertThat(environment.getProperty("spring.application.name")).isEqualTo("preorder");
        assertThat(environment.getProperty("spring.datasource.hikari.connection-timeout")).isEqualTo("2000");
        assertThat(environment.getProperty("auth.revocation-check.fail-closed-paths"))
                .isEqualTo("/api/v1/admin/**,/api/v1/preorders/*/cancel");
        assertThat(environment.getProperty("nova.outbox.metrics-prefix")).isEqualTo("preorder.outbox");
        assertThat(environment.getProperty("management.server.port")).isEqualTo("9083");
        assertThat(environment.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,prometheus");
        assertThat(environment.getProperty("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState");
        assertThat(environment.getProperty("management.metrics.distribution.percentiles-histogram.preorder.accept"))
                .isEqualTo("true");
    }

    @Test
    void DLQ_소비기는_받기_소비기를_따라_켜지고_설정으로_끌_수_있다() {
        assertThat(apply(new StandardEnvironment()).getProperty("nova.sqs.dead-letter.enabled")).isEqualTo("false");
        assertThat(apply(with(Map.of("nova.sqs.consumer.enabled", "true")))
                .getProperty("nova.sqs.dead-letter.enabled")).isEqualTo("true");
        assertThat(apply(with(Map.of("nova.sqs.consumer.enabled", "true", "nova.sqs.dead-letter.enabled", "false")))
                .getProperty("nova.sqs.dead-letter.enabled")).isEqualTo("false");
    }

    @Test
    void 관리_포트를_서비스_포트와_같게_두거나_끄면_기동을_막고_웹_없는_기동은_막지_않는다() {
        assertThatThrownBy(() -> apply(with(Map.of("server.port", "8083", "management.server.port", "8083"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SAME");
        assertThatThrownBy(() -> apply(with(Map.of("management.server.port", "-1"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DISABLED");
        assertThat(apply(with(Map.of("management.server.port", "-1", "spring.main.web-application-type", "none")))
                .getProperty("management.server.port")).isEqualTo("-1");
    }

    @Test
    void 설정_파일의_값이_기본값을_이긴다() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("application.yml",
                Map.of("resilience4j.bulkhead.configs.default.max-concurrent-calls", "5")));

        assertThat(apply(environment).getProperty("resilience4j.bulkhead.configs.default.max-concurrent-calls"))
                .isEqualTo("5");
    }


    private static StandardEnvironment with(Map<String, String> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new MapPropertySource("application.yml", Map.<String, Object>copyOf(properties)));
        return environment;
    }

    private static StandardEnvironment apply(StandardEnvironment environment) {
        new PreorderDefaults().postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }
}
