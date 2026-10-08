package com.grandis.nova.payment.config;

import com.grandis.nova.payment.support.PaymentIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Tomcat 두 개(서비스 포트 · 관리 포트)에 HTTP 로 묻는다 — MockMvc 는 관리 포트를 띄우지 않아 이 경우를 못 본다.
 * 관리 포트는 공통 시험 설정이 빈 포트(0)로 덮는다. 나머지는 PaymentDefaults 의 기본값 그대로다.
 */
@PaymentIntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class HealthEndpointTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @Value("${local.management.port}")
    int managementPort;

    @Autowired
    HealthEndpointGroups groups;

    @Autowired
    ApplicationContext context;

    @Test
    void 관리_포트의_readiness_liveness_는_토큰_없이_200_UP() throws Exception {
        assertThat(managementPort).isNotEqualTo(port);
        for (String path : new String[]{"/actuator/health/readiness", "/actuator/health/liveness"}) {
            HttpResponse<String> response = get(managementPort, path);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).as(path).isEqualTo("{\"status\":\"UP\"}");
        }
    }

    @Test
    void 트래픽을_거부하는_동안_readiness_는_503_이고_liveness_는_200_이다() throws Exception {
        AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
        try {
            assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(503);
            assertThat(get(managementPort, "/actuator/health/liveness").statusCode()).isEqualTo(200);
        } finally {
            // 컨텍스트를 다른 시험과 나눠 쓰므로 받는 상태로 되돌린다
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        }
        assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test
    void 서비스_포트로는_익명에게_health_가_열리지_않고_관리_포트도_health_밖은_막힌다() throws Exception {
        assertThat(get(port, "/actuator/health/readiness").statusCode()).isEqualTo(401);
        assertThat(get(managementPort, "/actuator/env").statusCode()).isEqualTo(401);
    }

    @Test
    void readiness_는_앱의_준비_상태만_본다() {
        assertThat(groups.getPrimary().isMember("db")).as("대조군 — DB 기여자의 이름은 db").isTrue();
        assertThat(groups.get("readiness").isMember("readinessState")).isTrue();
        assertThat(groups.get("readiness").isMember("db")).isFalse();
        assertThat(groups.get("readiness").isMember("redis")).isFalse();
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
