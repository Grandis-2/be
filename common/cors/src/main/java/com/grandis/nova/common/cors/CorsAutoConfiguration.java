package com.grandis.nova.common.cors;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * 웹 스택에 맞는 CorsConfigurationSource 빈 하나를 만든다(두 스택의 타입이 이름만 같아 설정을 나눴다).
 * 보안 체인(서블릿 SecurityFilterChainSupport · waitingroom)이 이 빈을 받아 CORS 를 건다 — 사전 요청은 인증 전에 답해 토큰 없이도 통과한다.
 */
@AutoConfiguration
@EnableConfigurationProperties(CorsProperties.class)
@Import({ServletCorsConfiguration.class, ReactiveCorsConfiguration.class})
public class CorsAutoConfiguration {

    /**
     * 소스 빈 이름. 서블릿 앱에서는 MVC 의 HandlerMappingIntrospector 도 같은 타입이라 받는 쪽은 이 이름으로 고른다.
     * 스프링 시큐리티가 기본으로 찾는 이름과 같다.
     */
    public static final String SOURCE_BEAN = "corsConfigurationSource";
    static final String ALL_PATHS = "/**";
}
