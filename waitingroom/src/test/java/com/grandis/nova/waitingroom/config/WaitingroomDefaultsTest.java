package com.grandis.nova.waitingroom.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WaitingroomDefaultsTest {

    @Test
    void 기본값을_넣되_설정이_같은_키를_주면_설정이_이긴다() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("yaml",
                Map.of("management.endpoints.web.exposure.include", "health")));

        new WaitingroomDefaults().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.application.name")).isEqualTo("waitingroom");
        assertThat(environment.getProperty("spring.reactor.context-propagation")).isEqualTo("auto");
        assertThat(environment.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
    }
}
