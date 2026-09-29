package com.grandis.nova.common.web;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAPI 문서 노출 정책. 설정이 다 모인 뒤(ConfigData 이후) 돌면서 값을 정규화해 맨 앞에 깐다.
 *
 * - springdoc.api-docs.enabled · swagger-ui.enabled 는 정확히 "true" 일 때만 켠다. springdoc 조건에는 havingValue 가 없어
 *   "false" 가 아닌 값(빈 값 · 0 · no · 오타)을 전부 켬으로 읽는다. api-docs 가 주 스위치이고 UI 는 그게 켜졌을 때만 켠다.
 * - /internal/** 은 nova.openapi.include-internal 이 정확히 "true" 가 아니면 paths-to-exclude 에 더한다.
 *   전역 목록이 차 있으면 springdoc 는 그룹별 제외(group-configs)를 보지 않는다 — 제외 경로는 전역에 적는다.
 *
 * 이 뒤에 붙는 속성 원천(시험의 @DynamicPropertySource 등)은 정규화되지 않는다.
 */
public class OpenApiExposure implements EnvironmentPostProcessor, Ordered {

    static final String API_DOCS = "springdoc.api-docs.enabled";
    static final String SWAGGER_UI = "springdoc.swagger-ui.enabled";
    static final String PATHS_TO_EXCLUDE = "springdoc.paths-to-exclude";
    static final String INCLUDE_INTERNAL = "nova.openapi.include-internal";
    static final String INTERNAL_PATHS = "/internal/**";
    static final String SOURCE_NAME = "novaOpenApiExposure";

    /** 설정 파일을 읽는 ConfigDataEnvironmentPostProcessor 뒤에 돈다. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean apiDocs = isExactlyTrue(environment.getProperty(API_DOCS));
        boolean swaggerUi = apiDocs && isExactlyTrue(environment.getProperty(SWAGGER_UI));

        List<String> excluded = new ArrayList<>(Binder.get(environment)
                .bind(PATHS_TO_EXCLUDE, Bindable.listOf(String.class)).orElse(List.of()));
        if (!isExactlyTrue(environment.getProperty(INCLUDE_INTERNAL)) && !excluded.contains(INTERNAL_PATHS)) {
            excluded.add(INTERNAL_PATHS);
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put(API_DOCS, String.valueOf(apiDocs));
        normalized.put(SWAGGER_UI, String.valueOf(swaggerUi));
        // 콤마로 이어 쓰면 목록 바인딩이 {id:[0-9]{1,3}} 같은 패턴을 쪼갠다. 인덱스 키는 맨 앞 원천에서 바인딩이 끝나 아래와 섞이지 않는다
        for (int i = 0; i < excluded.size(); i++) {
            normalized.put(PATHS_TO_EXCLUDE + "[" + i + "]", excluded.get(i));
        }
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, normalized));
    }

    static boolean isExactlyTrue(String value) {
        return value != null && value.strip().equalsIgnoreCase("true");
    }
}
