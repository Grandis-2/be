package com.grandis.nova.catalog.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogDefaultsTest {

    @Test
    @DisplayName("배포 설정이 비어 있으면 관리 포트 9082 의 health 만 내놓고, 배포 설정이 같은 키를 주면 그 값이 이긴다")
    void defaultsAreTheLowestPrecedence() {
        MockEnvironment empty = new MockEnvironment();
        new CatalogDefaults().postProcessEnvironment(empty, new SpringApplication());
        assertThat(empty.getProperty("management.server.port")).isEqualTo("9082");
        assertThat(empty.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");

        MockEnvironment overridden = new MockEnvironment();
        overridden.getPropertySources().addFirst(new MapPropertySource("deployed", Map.of("management.server.port", "9999")));
        new CatalogDefaults().postProcessEnvironment(overridden, new SpringApplication());
        assertThat(overridden.getProperty("management.server.port")).isEqualTo("9999");
    }

    @Test
    @DisplayName("Boot 가 읽도록 spring.factories 에 등록돼 있다 — 빠지면 관리 포트가 서비스 포트와 같아져 health 가 서비스 포트로 나간다")
    void registeredForBootToPickUp() {
        // 부트 자신의 후처리기 일부는 생성자 인자가 필요해 못 만든다 — 건너뛰되 이유는 모아 실패 메시지에 싣는다
        List<String> skipped = new ArrayList<>();
        assertThat(SpringFactoriesLoader.forDefaultResourceLocation().load(EnvironmentPostProcessor.class,
                SpringFactoriesLoader.FailureHandler.handleMessage((message, failure) -> skipped.add(message.get()))))
                .as("만들지 못한 후처리기: %s", skipped)
                .anyMatch(CatalogDefaults.class::isInstance);
    }

    @Test
    @DisplayName("배포 설정이 관리 포트를 서비스 포트와 같게 주거나 끄면 기동을 막는다 — 다르면 통과, 웹 서버 없는 기동은 막지 않음")
    void refusesManagementPortSharedWithTheServicePort() {
        assertThatThrownBy(() -> apply(Map.of("server.port", "8082", "management.server.port", "8082")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SAME");
        assertThatThrownBy(() -> apply(Map.of("management.server.port", "-1")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DISABLED");
        // 웹 서버를 띄우지 않는 기동은 포트가 없어 막지 않는다
        SpringApplication nonWeb = new SpringApplication();
        nonWeb.setWebApplicationType(WebApplicationType.NONE);
        MockEnvironment disabled = new MockEnvironment();
        disabled.getPropertySources().addFirst(new MapPropertySource("deployed", Map.of("management.server.port", "-1")));
        new CatalogDefaults().postProcessEnvironment(disabled, nonWeb);
        assertThat(disabled.getProperty("management.server.port")).isEqualTo("-1");
        assertThat(apply(Map.of("server.port", "8082")).getProperty("management.server.port")).isEqualTo("9082");
    }

    private static MockEnvironment apply(Map<String, Object> deployed) {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("deployed", deployed));
        new CatalogDefaults().postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }
}
