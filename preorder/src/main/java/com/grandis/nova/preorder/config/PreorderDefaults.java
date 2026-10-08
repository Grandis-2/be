package com.grandis.nova.preorder.config;

import com.grandis.nova.preorder.integration.Dependencies;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 설정 파일에 없어도 쓰이는 기본값. 가장 낮은 우선순위로 넣으므로 운영 yaml · 환경 변수가 같은 키를 주면 그 값이 이긴다.
 * 설정 파일은 저장소에 두지 않으므로 코드와 함께 배포할 기본값은 여기에 둔다.
 */
class PreorderDefaults implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "preorderDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        // 문서 그룹(/v3/api-docs/preorder) · 로그가 이 이름을 쓴다
        defaults.put("spring.application.name", "preorder");
        revocationFailClosedPaths(defaults);
        outboxMetrics(defaults);
        databasePool(defaults);
        dependencyTimeouts(defaults);
        dependencyResilience(defaults);
        deadLetterConsumer(defaults);
        management(defaults);
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, defaults));
        // 웹 서버를 띄우지 않는 기동(도구)에는 포트가 없다. web-application-type 은 이 후처리기 뒤에 들어가므로 환경에서 먼저 읽는다
        WebApplicationType webType = Binder.get(environment).bind("spring.main.web-application-type", WebApplicationType.class)
                .orElse(application.getWebApplicationType());
        if (webType != WebApplicationType.NONE) {
            requireSeparateManagementPort(environment);
        }
    }

    /**
     * 관리 포트가 서비스 포트와 같거나 꺼져 있으면 기동을 막는다. 같으면 health · prometheus 의 인증 없는 허용이 ALB 뒤 공개 포트에 걸리고,
     * 헬스 체크가 보는 관리 포트는 아무도 듣지 않는다. 배포 설정이 관리 포트를 서비스 포트로 덮는 실수를 기동에서 드러낸다.
     */
    static void requireSeparateManagementPort(ConfigurableEnvironment environment) {
        ManagementPortType type = ManagementPortType.get(environment);
        if (type != ManagementPortType.DIFFERENT) {
            throw new IllegalStateException("관리 포트를 서비스 포트와 다른 포트로 켜야 한다(같은 포트 SAME · 꺼짐 DISABLED 는 거부): "
                    + type + ", server.port=" + environment.getProperty("server.port")
                    + ", management.server.port=" + environment.getProperty("management.server.port"));
        }
    }

    /** 설정 파일을 모두 읽은 뒤에 돈다. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * 토큰 폐기 확인(Redis)이 실패할 때 막는 경로. 목록 밖은 통과시키고 경고 · 카운터만 남긴다 —
     * 오픈 순간의 접수 · 조회는 Redis 장애에도 받고, 되돌릴 수 없는 취소와 관리자 기능만 막는다.
     */
    private void revocationFailClosedPaths(Map<String, Object> defaults) {
        defaults.put("auth.revocation-check.fail-closed-paths", "/api/v1/admin/**,/api/v1/preorders/*/cancel");
    }

    /**
     * 아웃박스 지표 이름(preorder.outbox.publish · unpublished · unpublished.max.attempts). 대시보드 · 경보가 이 이름을 본다 —
     * 운영 설정에서 빠져도 공통 기본값(outbox.*)으로 조용히 바뀌지 않게 코드에 둔다.
     */
    private void outboxMetrics(Map<String, Object> defaults) {
        defaults.put("nova.outbox.metrics-prefix", "preorder.outbox");
    }

    /**
     * 커넥션을 기다리는 상한(ms). 가상 스레드라 요청이 풀 앞에 줄을 서는데, 기본 30초면 과부하 때 요청을 오래 붙잡는다.
     * 빨리 실패시켜 503 + Retry-After 로 돌려보낸다.
     */
    private void databasePool(Map<String, Object> defaults) {
        defaults.put("spring.datasource.hikari.connection-timeout", 2000);
    }

    /**
     * DLQ 를 DB 로 옮기는 소비기는 받기 소비기를 따라 켠다 — 받기를 켠 배포에서 이 키가 빠져도 DLQ 메시지가 SQS 에만 쌓였다
     * 보존 기간 뒤 사라지지 않게. 설정이 false 를 주면 끈다.
     */
    private void deadLetterConsumer(Map<String, Object> defaults) {
        defaults.put("nova.sqs.dead-letter.enabled", "${nova.sqs.consumer.enabled:false}");
    }

    /**
     * 관리 엔드포인트는 서비스 포트와 나눈 관리 포트로만 내놓는다(ALB 헬스 체크 · 지표 수집이 이 포트를 본다). readiness 는 앱 준비 상태만 본다 —
     * DB · Redis 를 넣으면 공용 의존성 장애 한 번에 모든 태스크가 빠진다. 요청 지연은 p99 를 보려고 히스토그램으로 낸다.
     */
    private void management(Map<String, Object> defaults) {
        defaults.put("management.server.port", "9083");
        defaults.put("management.endpoints.web.exposure.include", "health,prometheus");
        defaults.put("management.endpoint.health.probes.enabled", "true");
        defaults.put("management.endpoint.health.group.readiness.include", "readinessState");
        for (String metric : List.of("http.server.requests", "preorder.accept", "preorder.events.handle",
                "preorder.events.lag")) {
            defaults.put("management.metrics.distribution.percentiles-histogram." + metric, "true");
        }
    }

    /** 내부 호출의 연결 · 읽기 시간 상한. 없으면 HTTP 클라이언트가 응답을 끝없이 기다려 동시 호출 상한을 붙잡는다. */
    private void dependencyTimeouts(Map<String, Object> defaults) {
        for (String dependency : Dependencies.ALL) {
            defaults.put("spring.http.serviceclient." + dependency + ".connect-timeout", "300ms");
            defaults.put("spring.http.serviceclient." + dependency + ".read-timeout", "1s");
        }
    }

    /**
     * 내부 호출(catalog · order) 장애 대응. 재시도(연결 실패 · 5xx 만 한 번) → 서킷 브레이커(50건 중 실패 · 1초 넘는 호출이 절반이면
     * 10초 열림) → 동시 호출 상한(20, 기다리지 않음). 4xx 는 재시도하지도 실패로 세지도 않는다. 값은 부하 실측으로 확정한다.
     */
    private void dependencyResilience(Map<String, Object> defaults) {
        String clientError = "org.springframework.web.client.HttpClientErrorException";
        defaults.put("resilience4j.retry.configs.default.max-attempts", 2);
        defaults.put("resilience4j.retry.configs.default.wait-duration", "50ms");
        defaults.put("resilience4j.retry.configs.default.enable-exponential-backoff", true);
        defaults.put("resilience4j.retry.configs.default.exponential-backoff-multiplier", 2);
        defaults.put("resilience4j.retry.configs.default.enable-randomized-wait", true);
        defaults.put("resilience4j.retry.configs.default.randomized-wait-factor", 0.5);
        defaults.put("resilience4j.retry.configs.default.retry-exceptions",
                "org.springframework.web.client.ResourceAccessException,"
                        + "org.springframework.web.client.HttpServerErrorException");
        defaults.put("resilience4j.retry.configs.default.ignore-exceptions", clientError);
        defaults.put("resilience4j.circuitbreaker.configs.default.sliding-window-size", 50);
        defaults.put("resilience4j.circuitbreaker.configs.default.minimum-number-of-calls", 20);
        defaults.put("resilience4j.circuitbreaker.configs.default.failure-rate-threshold", 50);
        defaults.put("resilience4j.circuitbreaker.configs.default.slow-call-duration-threshold", "1s");
        defaults.put("resilience4j.circuitbreaker.configs.default.slow-call-rate-threshold", 50);
        defaults.put("resilience4j.circuitbreaker.configs.default.wait-duration-in-open-state", "10s");
        defaults.put("resilience4j.circuitbreaker.configs.default.permitted-number-of-calls-in-half-open-state", 5);
        // 상한 초과는 상대 장애가 아니라 우리 쪽 거절이라 회로의 실패로 세지 않는다(몰림만으로 회로가 열리지 않게)
        defaults.put("resilience4j.circuitbreaker.configs.default.ignore-exceptions",
                clientError + ",io.github.resilience4j.bulkhead.BulkheadFullException");
        defaults.put("resilience4j.bulkhead.configs.default.max-concurrent-calls", 20);
        defaults.put("resilience4j.bulkhead.configs.default.max-wait-duration", "0");
        for (String dependency : Dependencies.ALL) {
            defaults.put("resilience4j.retry.instances." + dependency + ".base-config", "default");
            defaults.put("resilience4j.circuitbreaker.instances." + dependency + ".base-config", "default");
            defaults.put("resilience4j.bulkhead.instances." + dependency + ".base-config", "default");
        }
    }
}
