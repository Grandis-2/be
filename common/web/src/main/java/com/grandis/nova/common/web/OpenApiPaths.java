package com.grandis.nova.common.web;

/**
 * OpenAPI 문서 경로(springdoc 기본값). 허용은 공통에서 주입하지 않고 각 서비스 SecurityConfig 가 한 줄로 명시한다 —
 * SecurityFilterChainSupport 가 인가 규칙을 인자로 강제하는 것과 같은 이유다. catalog · preorder 는 SecurityConfig 를 만들 때 넣는다.
 *
 * <pre>
 * .requestMatchers(HttpMethod.GET, OpenApiPaths.docs()).permitAll()
 * </pre>
 *
 * springdoc.*.path 로 경로를 바꾸면 이 목록과 어긋난다.
 */
public final class OpenApiPaths {

    private static final String[] DOCS = {
            "/v3/api-docs/**",
            "/v3/api-docs.yaml",    // /v3/api-docs/** 에 걸리지 않는다
            "/swagger-ui.html",
            "/swagger-ui/**",
    };

    /** 배열 상수는 원소를 바꿀 수 있어 복사본을 준다. */
    public static String[] docs() {
        return DOCS.clone();
    }

    private OpenApiPaths() {
    }
}
