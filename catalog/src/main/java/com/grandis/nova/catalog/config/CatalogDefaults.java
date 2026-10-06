package com.grandis.nova.catalog.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * 코드와 함께 배포하는 설정 기본값. 가장 낮은 우선순위라 yaml · 환경 변수가 같은 키를 주면 그 값이 이긴다.
 * 환경마다 다른 값(주소 · 비밀)은 여기 두지 않는다. 관리 포트는 ALB 헬스 체크와 맞춘 고정값이라 여기 둔다(waitingroom 과 같은 방식).
 */
class CatalogDefaults implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "catalogDefaults";

    static final Map<String, Object> DEFAULTS = Map.of(
            // 헬스는 서비스 포트(ALB 가 요청을 보내는 8082)와 나눈 관리 포트로만 내놓는다. ALB 헬스 체크는 이 포트의 readiness 를 본다
            "management.server.port", "9082",
            "management.endpoints.web.exposure.include", "health",
            // 헬스 체크가 보는 /actuator/health/readiness · liveness. Boot 4.1 은 기본으로 켜지만(실측: 이 줄 없이도 200) 버전이 바뀌어도
            // 경로가 사라지지 않게 고정한다
            "management.endpoint.health.probes.enabled", "true",
            // readiness 는 앱의 준비 상태만 본다(Boot 기본 구성과 같지만 고정한다). DB · Redis 를 넣으면 공용 의존성 장애 한 번에 모든 태스크가
            // 비정상이 돼 ECS 가 태스크를 갈아 끼운다
            "management.endpoint.health.group.readiness.include", "readinessState");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, DEFAULTS));
        // 웹 서버를 띄우지 않는 기동(배치 · 도구)에는 포트가 없다 — 관리 포트를 꺼 둔 채(-1) 띄워도 막지 않는다
        if (application.getWebApplicationType() != WebApplicationType.NONE) {
            requireSeparateManagementPort(environment);
        }
    }

    /**
     * 관리 포트가 서비스 포트와 같거나 꺼져 있으면 기동을 막는다. 같으면 헬스 허용 규칙(EndpointRequest)이 서비스 포트에 걸려 ALB 뒤 공개 포트로
     * health 가 나가고(실측: 같은 포트면 서비스 포트의 readiness 가 401 이 아니라 200), 대상 그룹이 보는 관리 포트는 아무도 듣지 않는다.
     * 배포 설정이 management.server.port 를 서비스 포트로 덮는 실수를 기동에서 드러낸다.
     */
    static void requireSeparateManagementPort(ConfigurableEnvironment environment) {
        ManagementPortType type = ManagementPortType.get(environment);
        if (type != ManagementPortType.DIFFERENT) {
            throw new IllegalStateException("관리 포트를 서비스 포트와 다른 포트로 켜야 한다(헬스 체크는 관리 포트로만 — 같은 포트 SAME · 꺼짐 DISABLED 는 거부): "
                    + type + ", server.port=" + environment.getProperty("server.port")
                    + ", management.server.port=" + environment.getProperty("management.server.port"));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
