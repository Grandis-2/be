package com.grandis.nova.common.cors;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.reactive.CorsConfigurationSource;

import static com.grandis.nova.common.cors.CorsRulesAssert.ORIGINS;
import static com.grandis.nova.common.cors.CorsRulesAssert.SOURCE_BEAN;
import static org.assertj.core.api.Assertions.assertThat;

class ReactiveCorsConfigurationTest {

    @Test
    void 리액티브_앱에는_같은_규칙의_리액티브_소스_하나를_만든다() {
        new ReactiveWebApplicationContextRunner().withConfiguration(AutoConfigurations.of(CorsAutoConfiguration.class))
                .withPropertyValues(ORIGINS).run(context -> {
                    assertThat(context).hasSingleBean(CorsConfigurationSource.class);
                    assertThat(context.getBean(SOURCE_BEAN)).isInstanceOf(CorsConfigurationSource.class);
                    CorsRulesAssert.assertRules(context.getBean(CorsConfigurationSource.class)
                            .getCorsConfiguration(MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/preorders"))));
                });
    }
}
