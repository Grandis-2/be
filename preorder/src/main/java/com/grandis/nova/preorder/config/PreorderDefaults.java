package com.grandis.nova.preorder.config;

import com.grandis.nova.preorder.integration.Dependencies;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
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
        dependencyTimeouts(defaults);
        dependencyResilience(defaults);
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, defaults));
    }

    /** 설정 파일을 모두 읽은 뒤에 돈다. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /** 내부 호출의 연결 · 읽기 시간 상한. 없으면 HTTP 클라이언트가 응답을 끝없이 기다려 동시 호출 상한을 붙잡는다. */
    private static void dependencyTimeouts(Map<String, Object> defaults) {
        for (String dependency : Dependencies.ALL) {
            defaults.put("spring.http.serviceclient." + dependency + ".connect-timeout", "300ms");
            defaults.put("spring.http.serviceclient." + dependency + ".read-timeout", "1s");
        }
    }

    /**
     * 내부 호출(catalog · order) 장애 대응. 재시도(연결 실패 · 5xx 만 한 번) → 서킷 브레이커(50건 중 실패 · 1초 넘는 호출이 절반이면
     * 10초 열림) → 동시 호출 상한(20, 기다리지 않음). 4xx 는 재시도하지도 실패로 세지도 않는다. 값은 부하 실측으로 확정한다.
     */
    private static void dependencyResilience(Map<String, Object> defaults) {
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
