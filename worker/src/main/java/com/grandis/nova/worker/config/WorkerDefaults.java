package com.grandis.nova.worker.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * 코드와 함께 배포하는 설정 기본값. 가장 낮은 우선순위라 설정 파일 · 환경 변수가 같은 키를 주면 그 값이 이긴다.
 * 환경마다 다른 값(주소 · 지역)은 여기 두지 않는다. worker 의 운영값 기본은 {@link WorkerProperties} 가 가진다.
 */
class WorkerDefaults implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "workerDefaults";

    static final Map<String, Object> DEFAULTS = Map.of(
            "spring.application.name", "worker",
            // 서비스 API 가 없어 포트는 하나다. 헬스 체크 · 지표 수집이 이 포트를 본다
            "server.port", "9087",
            "management.endpoints.web.exposure.include", "health,prometheus",
            "management.endpoint.health.probes.enabled", "true",
            // readiness 는 앱의 준비 상태만 본다. 외부 mock · SQS 를 넣으면 그쪽 장애 한 번에 모든 태스크가 비정상이 된다
            "management.endpoint.health.group.readiness.include", "readinessState");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, DEFAULTS));
        // web-application-type 은 이 후처리기 뒤에 들어가므로 환경에서 먼저 읽는다
        WebApplicationType webType = Binder.get(environment)
                .bind("spring.main.web-application-type", WebApplicationType.class)
                .orElse(application.getWebApplicationType());
        if (webType == WebApplicationType.NONE) {
            throw new IllegalStateException("worker 는 웹 서버를 켜야 한다(spring.main.web-application-type=none 거부) — "
                    + "웹 서버가 없으면 health · 지표가 HTTP 로 열리지 않아 헬스 체크가 실패한다");
        }
    }

    /** 설정 파일을 모두 읽은 뒤에 돈다. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
