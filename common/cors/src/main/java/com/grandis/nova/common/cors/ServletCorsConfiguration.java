package com.grandis.nova.common.cors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** CorsAutoConfiguration 이 import 로만 등록한다 — @Configuration 을 붙이면 서비스의 컴포넌트 스캔(com.grandis.nova)에도 잡힌다. */
@ConditionalOnWebApplication(type = Type.SERVLET)
class ServletCorsConfiguration {

    @Bean(CorsAutoConfiguration.SOURCE_BEAN)
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties, Environment environment) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration(CorsAutoConfiguration.ALL_PATHS, CorsRules.configuration(properties, environment));
        return source;
    }
}
