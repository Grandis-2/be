package com.grandis.nova.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenApiHubTest {

    @Test
    void registeredForBootToPickUp() {
        assertThat(SpringFactoriesLoader.forDefaultResourceLocation().load(EnvironmentPostProcessor.class,
                SpringFactoriesLoader.FailureHandler.handleMessage((message, failure) -> { })))
                .anyMatch(OpenApiHub.class::isInstance);
    }

    @Test
    void listsEachServiceGroupInOrder() {
        StandardEnvironment environment = apply(" member, catalog ,preorder,order,member,");

        assertThat(environment.getProperty(OpenApiHub.URLS + "[0].name")).isEqualTo("member");
        assertThat(environment.getProperty(OpenApiHub.URLS + "[0].url")).isEqualTo("/v3/api-docs/member");
        assertThat(environment.getProperty(OpenApiHub.URLS + "[3].name")).isEqualTo("order");
        assertThat(environment.getProperty(OpenApiHub.URLS + "[4].name")).isNull();   // 중복 · 빈 항목은 빠진다
        assertThat(environment.getProperty(OpenApiHub.PRIMARY)).isEqualTo("member");
    }

    /** 허브가 아닌 서비스는 값이 없다 — springdoc 기본(자기 그룹만)을 그대로 둔다. */
    @Test
    void nothingWhenNotAHub() {
        StandardEnvironment environment = apply(null);

        assertThat(environment.getPropertySources().contains(OpenApiHub.SOURCE_NAME)).isFalse();
    }

    @Test
    void rejectsNamesThatCouldBendThePath() {
        for (String bad : new String[]{"member,../admin", "Member", "http://x", "a/b", "javascript:x"}) {
            assertThatThrownBy(() -> apply(bad)).as(bad).isInstanceOf(IllegalStateException.class);
        }
    }

    private static StandardEnvironment apply(String hubServices) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        if (hubServices != null) {
            environment.getPropertySources().addLast(new MapPropertySource("applicationConfig",
                    Map.of(OpenApiHub.HUB_SERVICES, hubServices)));
        }
        new OpenApiHub().postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }
}
