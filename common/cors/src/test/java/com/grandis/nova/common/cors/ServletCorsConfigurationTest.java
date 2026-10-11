package com.grandis.nova.common.cors;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfigurationSource;

import static com.grandis.nova.common.cors.CorsRulesAssert.ORIGINS;
import static com.grandis.nova.common.cors.CorsRulesAssert.SOURCE_BEAN;
import static org.assertj.core.api.Assertions.assertThat;

class ServletCorsConfigurationTest {

    static final AutoConfigurations AUTO = AutoConfigurations.of(CorsAutoConfiguration.class);

    @Test
    void 서블릿_앱에는_모든_경로에_같은_규칙을_거는_서블릿_소스_하나를_만든다() {
        new WebApplicationContextRunner().withConfiguration(AUTO).withPropertyValues(ORIGINS).run(context -> {
            assertThat(context).hasSingleBean(CorsConfigurationSource.class);
            assertThat(context.getBean(SOURCE_BEAN)).isInstanceOf(CorsConfigurationSource.class);
            CorsRulesAssert.assertRules(context.getBean(CorsConfigurationSource.class)
                    .getCorsConfiguration(new MockHttpServletRequest("POST", "/api/v1/preorders/any/cancel")));
        });
    }

    @Test
    void 웹_앱이_아니면_소스를_만들지_않는다() {
        new ApplicationContextRunner().withConfiguration(AUTO).withPropertyValues(ORIGINS)
                .run(context -> assertThat(context).doesNotHaveBean(SOURCE_BEAN));
    }

    @Test
    void 허용_오리진의_모양이_틀리면_기동_실패다() {
        new WebApplicationContextRunner().withConfiguration(AUTO)
                .withPropertyValues("nova.cors.allowed-origins=https://grandis-nova.link/")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 재발급_허용_오리진도_CORS_허용에_더한다_중복은_한_번() {
        new WebApplicationContextRunner().withConfiguration(AUTO)
                .withPropertyValues(ORIGINS, "auth.refresh.allowed-origins=http://localhost:3000,https://grandis-nova.link")
                .run(context -> assertThat(context.getBean(CorsConfigurationSource.class)
                        .getCorsConfiguration(new MockHttpServletRequest("POST", "/api/v1/session/refresh"))
                        .getAllowedOrigins()).containsExactly("https://grandis-nova.link", "http://localhost:3000"));
    }

    @Test
    void 재발급_허용_오리진의_모양이_틀리면_기동_실패다() {
        new WebApplicationContextRunner().withConfiguration(AUTO)
                .withPropertyValues(ORIGINS, "auth.refresh.allowed-origins=*")
                .run(context -> assertThat(context).hasFailed());
    }
}
