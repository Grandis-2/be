package com.grandis.nova.preorder.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 설정 파일에 값이 없으면 코드 기본값을 쓰고, 있으면 설정 파일 값이 이긴다. */
class PreorderDefaultsTest {

    @Test
    void 설정에_없으면_기본값을_쓴다() {
        StandardEnvironment environment = apply(new StandardEnvironment());

        assertThat(environment.getProperty("resilience4j.bulkhead.configs.default.max-concurrent-calls")).isEqualTo("20");
        assertThat(environment.getProperty("resilience4j.bulkhead.instances.order.base-config")).isEqualTo("default");
        assertThat(environment.getProperty("spring.http.serviceclient.catalog.read-timeout")).isEqualTo("1s");
        assertThat(environment.getProperty("spring.http.serviceclient.order.connect-timeout")).isEqualTo("300ms");
    }

    @Test
    void 설정_파일의_값이_기본값을_이긴다() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("application.yml",
                Map.of("resilience4j.bulkhead.configs.default.max-concurrent-calls", "5")));

        assertThat(apply(environment).getProperty("resilience4j.bulkhead.configs.default.max-concurrent-calls"))
                .isEqualTo("5");
    }


    private static StandardEnvironment apply(StandardEnvironment environment) {
        new PreorderDefaults().postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }
}
