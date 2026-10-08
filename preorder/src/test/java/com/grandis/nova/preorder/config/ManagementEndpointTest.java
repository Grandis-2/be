package com.grandis.nova.preorder.config;

import com.grandis.nova.preorder.support.DeadLetterTestConfig;
import com.grandis.nova.preorder.support.MySqlTestConfig;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.SecurityTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Tomcat 두 개(서비스 포트 · 관리 포트)에 HTTP 로 묻는다 — MockMvc 는 관리 포트를 띄우지 않아 이 경우를 못 본다.
 * 관리 포트는 시험끼리 겹치지 않게 0(아무 빈 포트)으로 덮는다. 나머지는 PreorderDefaults 의 기본값 그대로다.
 * 시험은 지표 내보내기를 끄므로(prometheus 엔드포인트가 없다) 켜서 띄운다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "nova.admission-ticket.secret=" + PreorderIntegrationTest.ADMISSION_TICKET_SECRET,
        "management.server.port=0"
})
@ActiveProfiles("test")
@AutoConfigureMetrics
@Import({MySqlTestConfig.class, SecurityTestConfig.class, DeadLetterTestConfig.class})
class ManagementEndpointTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @Value("${local.management.port}")
    int managementPort;

    @Autowired
    HealthEndpointGroups groups;

    @Test
    void 관리_포트의_readiness_liveness_prometheus_는_토큰_없이_열린다() throws Exception {
        assertThat(managementPort).isNotEqualTo(port);
        for (String path : new String[]{"/actuator/health/readiness", "/actuator/health/liveness"}) {
            HttpResponse<String> response = get(managementPort, path);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).as(path).isEqualTo("{\"status\":\"UP\"}");
        }
        HttpResponse<String> prometheus = get(managementPort, "/actuator/prometheus");
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body()).contains("# TYPE preorder_outbox_unpublished gauge");
    }

    @Test
    void 서비스_포트로는_익명에게_관리_엔드포인트가_열리지_않고_관리_포트도_health_prometheus_밖은_막힌다() throws Exception {
        assertThat(get(port, "/actuator/health/readiness").statusCode()).isEqualTo(401);
        assertThat(get(port, "/actuator/prometheus").statusCode()).isEqualTo(401);
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
