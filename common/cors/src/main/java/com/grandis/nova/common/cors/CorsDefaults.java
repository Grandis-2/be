package com.grandis.nova.common.cors;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 허용 오리진 기본값 — 운영 프론트 두 도메인과 API 자기 도메인. 가장 낮은 우선순위라 설정이 주면 그 목록이 이긴다.
 * API 도메인은 그 도메인에서 뜬 Swagger UI 의 호출 때문이다. TLS 를 ALB 가 풀어 앱은 자기를 http 로 보므로
 * 같은 출처 호출도 CORS 요청으로 판정된다.
 */
class CorsDefaults implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "novaCorsDefaults";
    static final String ALLOWED_ORIGINS = "nova.cors.allowed-origins";
    static final List<String> ORIGINS = List.of(
            "https://grandis-nova.link", "https://www.grandis-nova.link", "https://api.grandis-nova.link");

    /** 인덱스 키로 깐다 — 목록은 값을 가진 가장 앞 원천 하나에서만 바인딩되므로 설정의 목록과 섞이지 않는다. */
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (int i = 0; i < ORIGINS.size(); i++) {
            defaults.put(ALLOWED_ORIGINS + "[" + i + "]", ORIGINS.get(i));
        }
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, defaults));
    }

    /** 설정 파일 · Parameter Store 를 모두 읽은 뒤에 돈다. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
