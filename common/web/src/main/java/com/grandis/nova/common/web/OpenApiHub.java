package com.grandis.nova.common.web;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 문서 허브. nova.openapi.hub-services(예: member,catalog,preorder,order)를 가진 서비스의 Swagger UI 가
 * 각 서비스의 그룹 문서(/v3/api-docs/{서비스})를 드롭다운으로 보여 준다. 허브는 member 이고 값은 member 설정에만 둔다.
 *
 * - 주소가 상대 경로라 한 출처 뒤(dev: ALB, 로컬: docker/openapi-hub)에서만 모두 열린다. 허브 포트로 바로 열면 자기 문서만 된다.
 * - springdoc 는 드롭다운을 이름순으로 정렬하므로, 처음 열릴 문서는 목록의 첫 서비스로 정한다.
 * - 이 값이 있으면 springdoc.swagger-ui.urls · urls-primary-name 을 직접 준 값은 덮인다.
 * - 서비스 이름은 경로에 들어가므로 소문자 · 숫자 · 하이픈만 받는다. 어긋나면 문서가 꺼져 있어도 기동을 멈춘다.
 */
public class OpenApiHub implements EnvironmentPostProcessor, Ordered {

    static final String HUB_SERVICES = "nova.openapi.hub-services";
    static final String URLS = "springdoc.swagger-ui.urls";
    static final String PRIMARY = "springdoc.swagger-ui.urls-primary-name";
    static final String SOURCE_NAME = "novaOpenApiHub";
    private static final Pattern SERVICE_NAME = Pattern.compile("[a-z0-9-]+");

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<String> services = parse(environment.getProperty(HUB_SERVICES));
        if (services.isEmpty()) {
            return;
        }
        Map<String, Object> urls = new LinkedHashMap<>();
        urls.put(PRIMARY, services.getFirst());
        for (int i = 0; i < services.size(); i++) {
            urls.put(URLS + "[" + i + "].name", services.get(i));
            urls.put(URLS + "[" + i + "].url", "/v3/api-docs/" + services.get(i));
        }
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, urls));
    }

    static List<String> parse(String value) {
        if (value == null) {
            return List.of();
        }
        List<String> services = Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(name -> !name.isEmpty())
                .distinct()
                .toList();
        services.stream().filter(name -> !SERVICE_NAME.matcher(name).matches()).findFirst().ifPresent(name -> {
            throw new IllegalStateException(HUB_SERVICES + " 의 서비스 이름은 소문자 · 숫자 · 하이픈만 쓴다: " + name);
        });
        return services;
    }
}
