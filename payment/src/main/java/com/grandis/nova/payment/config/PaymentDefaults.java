package com.grandis.nova.payment.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * 코드와 함께 배포하는 설정 기본값. 가장 낮은 우선순위라 설정 파일 · 환경 변수가 같은 키를 주면 그 값이 이긴다.
 * 환경마다 다른 값(주소 · 비밀)은 여기 두지 않는다. 관리 포트는 ALB 헬스 체크와 맞춘 고정값이라 여기 둔다.
 */
class PaymentDefaults implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "paymentDefaults";

    static final Map<String, Object> DEFAULTS = Map.of(
            // 헬스는 서비스 포트(8085)와 나눈 관리 포트로만 내놓는다. ALB 헬스 체크는 이 포트의 readiness 를 본다
            "management.server.port", "9086",
            "management.endpoints.web.exposure.include", "health",
            "management.endpoint.health.probes.enabled", "true",
            // readiness 는 앱의 준비 상태만 본다. DB · Redis 를 넣으면 공용 의존성 장애 한 번에 모든 태스크가 비정상이 된다
            "management.endpoint.health.group.readiness.include", "readinessState");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, DEFAULTS));
        // 웹 서버를 띄우지 않는 기동(도구)에는 포트가 없다. web-application-type 은 이 후처리기 뒤에 들어가므로 환경에서 먼저 읽는다
        WebApplicationType webType = Binder.get(environment)
                .bind("spring.main.web-application-type", WebApplicationType.class)
                .orElse(application.getWebApplicationType());
        if (webType != WebApplicationType.NONE) {
            requireSeparateManagementPort(environment);
        }
    }

    /**
     * 관리 포트가 서비스 포트와 같거나 꺼져 있으면 기동을 막는다. 같으면 health 의 인증 없는 허용이 ALB 뒤 공개 포트에 걸리고,
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
}
