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

    static final Map<String, Object> DEFAULTS = Map.ofEntries(
            Map.entry("spring.application.name", "waitingroom"),
            Map.entry("spring.main.web-application-type", "reactive"),
            // Reactor 컨텍스트의 traceId 를 스레드가 바뀌어도 로그 MDC 에 싣는다
            Map.entry("spring.reactor.context-propagation", "auto"),
            Map.entry("logging.pattern.correlation", "[%X{traceId:-}] "),
            // 지표 · 헬스는 서비스 포트(ALB)와 나눈 관리 포트로만 내놓는다. ALB 헬스 체크는 이 포트의 readiness 를 본다
            Map.entry("management.server.port", "9085"),
            Map.entry("management.endpoints.web.exposure.include", "health,prometheus"),
            // 판정 재료를 한 번도 못 받았으면 받지 않는다. Redis 같은 공용 의존성 장애로는 내리지 않는다(503 으로 답한다)
            Map.entry("management.endpoint.health.group.readiness.include", "readinessState,jwks,snapshot"),
            // 제어 루프가 멈췄을 때만 다시 띄운다 — Redis 장애로 전 태스크가 재시작되지 않게
            Map.entry("management.endpoint.health.group.liveness.include", "livenessState,controlLoop"),
            // 종료하면 readiness 를 먼저 내리고 받던 요청을 마저 처리한다
            Map.entry("server.shutdown", "graceful"),
            Map.entry("spring.lifecycle.timeout-per-shutdown-phase", "20s"),
            // 명령 하나가 틱(1초)을, 연결이 리더 리스(2초)를 넘겨 붙들지 않게
            Map.entry("spring.data.redis.timeout", "500ms"),
            Map.entry("spring.data.redis.connect-timeout", "1s"),
            // 접수 전달. 응답이 늦으면 결과를 모르는 채로 끊기므로 접수 처리 시간보다 넉넉히 둔다
            Map.entry("spring.cloud.gateway.server.webflux.httpclient.connect-timeout", "1000"),
            Map.entry("spring.cloud.gateway.server.webflux.httpclient.response-timeout", "10s"),
            // preorder(Tomcat)가 먼저 닫은 연결을 다시 쓰면 보낸 뒤 끊겨 결과를 모른다 — 그쪽 유휴 시한보다 먼저 버린다
            Map.entry("spring.cloud.gateway.server.webflux.httpclient.pool.max-idle-time", "15s"),
            // 접수는 대략 배분 속도(전역 초당 입장)로 온다. 연결 수를 묶어 preorder 가 느려져도 쌓이지 않게 하고,
            // 빈 연결을 기다리다 시한이 지나면 보내지 않은 것이라 503 으로 다시 오게 한다
            Map.entry("spring.cloud.gateway.server.webflux.httpclient.pool.type", "fixed"),
            Map.entry("spring.cloud.gateway.server.webflux.httpclient.pool.max-connections", "100"),
            Map.entry("spring.cloud.gateway.server.webflux.httpclient.pool.acquire-timeout", "2000"));

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, DEFAULTS));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
