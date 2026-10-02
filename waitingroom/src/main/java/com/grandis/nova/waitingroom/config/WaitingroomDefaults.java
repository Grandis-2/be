package com.grandis.nova.waitingroom.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * 코드와 함께 배포하는 설정 기본값. 가장 낮은 우선순위라 yaml · 환경 변수가 같은 키를 주면 그 값이 이긴다.
 * 환경마다 다른 값(주소 · 비밀)은 여기 두지 않는다. 관리 포트는 ALB 헬스 체크와 맞춘 고정값이라 여기 둔다.
 */
class WaitingroomDefaults implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "waitingroomDefaults";

    static final Map<String, Object> DEFAULTS = Map.of(
            "spring.application.name", "waitingroom",
            "spring.main.web-application-type", "reactive",
            // Reactor 컨텍스트의 traceId 를 스레드가 바뀌어도 로그 MDC 에 싣는다
            "spring.reactor.context-propagation", "auto",
            "logging.pattern.correlation", "[%X{traceId:-}] ",
            // 지표 · 헬스는 서비스 포트(ALB)와 나눈 관리 포트로만 내놓는다. ALB 헬스 체크는 이 포트의 readiness 를 본다
            "management.server.port", "9085",
            "management.endpoints.web.exposure.include", "health,prometheus",
            "management.endpoint.health.group.readiness.include", "readinessState,jwks",
            // 명령 하나가 틱(1초)을, 연결이 리더 리스(2초)를 넘겨 붙들지 않게
            "spring.data.redis.timeout", "500ms",
            "spring.data.redis.connect-timeout", "1s");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, DEFAULTS));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
