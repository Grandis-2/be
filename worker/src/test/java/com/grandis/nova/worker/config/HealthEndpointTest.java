package com.grandis.nova.worker.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 웹 서버에 HTTP 로 묻는다. DB · SQS 없이 뜨고, 열리는 것은 health 와 지표뿐이다. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "nova.worker.mock.base-url=http://mock.nova.local:8081")
@AutoConfigureMetrics
class HealthEndpointTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @Autowired
    HealthEndpointGroups groups;

    @Autowired
    WorkerProperties properties;

    @Test
    void readiness_liveness_는_200_UP() throws Exception {
        for (String path : new String[]{"/actuator/health/readiness", "/actuator/health/liveness"}) {
            HttpResponse<String> response = get(path);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).as(path).isEqualTo("{\"status\":\"UP\"}");
        }
    }

    @Test
    void 지표는_열리고_그_밖의_관리_기능은_열리지_않는다() throws Exception {
        assertThat(get("/actuator/prometheus").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
    }

    /** 수집기(Prometheus · CloudWatch 에이전트)가 긁어 가는 출력에 차단기 상태와 자리 수가 호출 전부터 있다. */
    @Test
    void 차단기_상태와_동시_요청_자리가_지표로_나간다() throws Exception {
        String metrics = get("/actuator/prometheus").body();

        assertThat(metrics).containsPattern("resilience4j_circuitbreaker_state\\{[^}]*name=\"mock\"[^}]*state=\"open\"[^}]*} 0");
        assertThat(metrics).containsPattern("resilience4j_circuitbreaker_state\\{[^}]*name=\"mock\"[^}]*state=\"closed\"[^}]*} 1");
        assertThat(metrics).containsPattern("resilience4j_circuitbreaker_not_permitted_calls_total\\{[^}]*name=\"mock\"");
        assertThat(metrics).containsPattern("resilience4j_ratelimiter_available_permissions\\{[^}]*name=\"mock\"");
        assertThat(metrics).containsPattern("worker_concurrency_limit(\\{[^}]*})? 8");
        assertThat(metrics).containsPattern("worker_concurrency_in_use(\\{[^}]*})? 0");
        assertThat(metrics).containsPattern("worker_receive_blocked(\\{[^}]*})? 0");
    }

    @Test
    void readiness_는_앱의_준비_상태만_본다() {
        assertThat(groups.get("readiness").isMember("readinessState")).isTrue();
        assertThat(groups.get("readiness").isMember("diskSpace")).isFalse();
    }

    @Test
    void 운영값이_기본값으로_묶인다() {
        assertThat(properties.concurrency().limit()).isEqualTo(16);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
