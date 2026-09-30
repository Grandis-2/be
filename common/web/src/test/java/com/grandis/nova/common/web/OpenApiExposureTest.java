package com.grandis.nova.common.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 켜짐은 정확히 "true" 뿐이다. 제외 목록은 springdoc 와 같은 목록 바인딩으로 확인한다. */
class OpenApiExposureTest {

    @Test
    void registeredForBootToPickUp() {
        // 부트 자신의 후처리기는 생성자 인자가 필요해 못 만든다 — 건너뛴다
        assertThat(SpringFactoriesLoader.forDefaultResourceLocation().load(EnvironmentPostProcessor.class,
                SpringFactoriesLoader.FailureHandler.handleMessage((message, failure) -> { })))
                .anyMatch(OpenApiExposure.class::isInstance);
    }

    @Test
    void runsAfterConfigFilesAreLoaded() {
        assertThat(new OpenApiExposure().getOrder()).isGreaterThan(ConfigDataEnvironmentPostProcessor.ORDER);
    }

    @Test
    void offWhenNothingIsSet() {
        StandardEnvironment environment = apply(Map.of());

        assertThat(environment.getProperty(OpenApiExposure.API_DOCS)).isEqualTo("false");
        assertThat(environment.getProperty(OpenApiExposure.SWAGGER_UI)).isEqualTo("false");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "0", "1", "no", "off", "yes", "on", "flase", "ture", "true1", "false"})
    void anythingButTrueIsOff(String value) {
        StandardEnvironment environment = apply(Map.of(
                OpenApiExposure.API_DOCS, value,
                OpenApiExposure.SWAGGER_UI, value));

        assertThat(environment.getProperty(OpenApiExposure.API_DOCS)).isEqualTo("false");
        assertThat(environment.getProperty(OpenApiExposure.SWAGGER_UI)).isEqualTo("false");
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "True", " true ", "true\n"})
    void exactlyTrueIsOn(String value) {
        StandardEnvironment environment = apply(Map.of(
                OpenApiExposure.API_DOCS, value,
                OpenApiExposure.SWAGGER_UI, value));

        assertThat(environment.getProperty(OpenApiExposure.API_DOCS)).isEqualTo("true");
        assertThat(environment.getProperty(OpenApiExposure.SWAGGER_UI)).isEqualTo("true");
    }

    @Test
    void swaggerUiAloneTurnsNothingOn() {
        StandardEnvironment environment = apply(Map.of(OpenApiExposure.SWAGGER_UI, "true"));

        assertThat(environment.getProperty(OpenApiExposure.API_DOCS)).isEqualTo("false");
        assertThat(environment.getProperty(OpenApiExposure.SWAGGER_UI)).isEqualTo("false");
    }

    @Test
    void environmentVariablesAreNormalizedToo() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("SPRINGDOC_API_DOCS_ENABLED", "true", "SPRINGDOC_SWAGGER_UI_ENABLED", "")));
        new OpenApiExposure().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(OpenApiExposure.API_DOCS)).isEqualTo("true");
        assertThat(environment.getProperty(OpenApiExposure.SWAGGER_UI)).isEqualTo("false");   // 빈 템플릿 변수
    }

    @Test
    void internalPathsAreExcludedByDefault() {
        StandardEnvironment environment = apply(Map.of(OpenApiExposure.API_DOCS, "true"));

        assertThat(excluded(environment)).containsExactly("/internal/**");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "yes", "1", "flase"})
    void internalPathsStayExcludedUnlessExactlyTrue(String value) {
        StandardEnvironment environment = apply(Map.of(OpenApiExposure.INCLUDE_INTERNAL, value));

        assertThat(excluded(environment)).containsExactly("/internal/**");
    }

    @Test
    void internalPathsAreIncludedOnlyOnRequest() {
        StandardEnvironment environment = apply(Map.of(OpenApiExposure.INCLUDE_INTERNAL, "true"));

        assertThat(excluded(environment)).isEmpty();
    }

    @Test
    void serviceExclusionsAreKept() {
        Map<String, Object> config = new HashMap<>();
        config.put(OpenApiExposure.PATHS_TO_EXCLUDE + "[0]", "/health/**");
        config.put(OpenApiExposure.PATHS_TO_EXCLUDE + "[1]", "/actuator/**");

        assertThat(excluded(apply(config))).containsExactly("/health/**", "/actuator/**", "/internal/**");
        assertThat(excluded(apply(Map.of(OpenApiExposure.PATHS_TO_EXCLUDE, "/health/**,/actuator/**"))))
                .containsExactly("/health/**", "/actuator/**", "/internal/**");
    }

    @Test
    void patternsWithCommasSurviveIntact() {
        Map<String, Object> config = new HashMap<>();
        config.put(OpenApiExposure.PATHS_TO_EXCLUDE + "[0]", "/x/{id:[0-9]{1,3}}");
        config.put(OpenApiExposure.PATHS_TO_EXCLUDE + "[1]", "/a/**");

        assertThat(excluded(apply(config))).containsExactly("/x/{id:[0-9]{1,3}}", "/a/**", "/internal/**");
    }

    @Test
    void normalizedListIsNotMixedWithLowerSources() {
        StandardEnvironment environment = apply(Map.of(OpenApiExposure.INCLUDE_INTERNAL, "true"));
        environment.getPropertySources().addLast(new MapPropertySource("lower", Map.of(
                OpenApiExposure.PATHS_TO_EXCLUDE + "[0]", "/stale-0/**",
                OpenApiExposure.PATHS_TO_EXCLUDE + "[1]", "/stale-1/**")));
        environment.getPropertySources().addFirst(new MapPropertySource("upper", Map.of(
                OpenApiExposure.PATHS_TO_EXCLUDE + "[0]", "/only/**")));

        assertThat(excluded(environment)).containsExactly("/only/**");
    }

    private static List<String> excluded(StandardEnvironment environment) {
        return Binder.get(environment).bind(OpenApiExposure.PATHS_TO_EXCLUDE, Bindable.listOf(String.class))
                .orElse(List.of());
    }

    private static StandardEnvironment apply(Map<String, Object> config) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addLast(new MapPropertySource("applicationConfig", config));
        new OpenApiExposure().postProcessEnvironment(environment, new SpringApplication());
        return environment;
    }
}
