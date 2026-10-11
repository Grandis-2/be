package com.grandis.nova.catalog.config;

import com.grandis.nova.catalog.support.AccessTokens;
import com.grandis.nova.catalog.support.MySqlContainerConfig;
import com.grandis.nova.catalog.support.RedisContainerConfig;
import com.grandis.nova.catalog.support.SecurityTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Tomcat 두 개(서비스 포트 · 관리 포트)에 HTTP 로 묻는다 — MockMvc 는 관리 포트의 자식 컨텍스트를 띄우지 않아 이 경우를 못 본다.
 * 관리 포트는 시험끼리 겹치지 않게 0(아무 빈 포트)으로 덮는다. 나머지는 CatalogDefaults 의 기본값 그대로다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
        "spring.flyway.locations=filesystem:${nova.migrations-path}",
        "spring.flyway.default-schema=shop",
        "spring.flyway.schemas=shop",
        "spring.flyway.create-schemas=false",
        "spring.flyway.clean-disabled=true",
        "spring.flyway.validate-migration-naming=true",
        "spring.threads.virtual.enabled=true",
        // 검증만 한다(개인키 없음). 발급자 · 유효기간은 member 와 같은 값 — 발급하지 않아도 필수 값이다
        "jwt.issuer=" + AccessTokens.ISSUER,
        // Redis 가 죽은 갈래를 시험할 때 기본값(60s)이면 시험이 멈춰 선다. 예시 설정과 같은 값
        "spring.data.redis.timeout=300ms",
        "spring.data.redis.connect-timeout=200ms",
        // 아웃박스는 로그로 보낸다(큐 없이 흐름이 끝까지 돈다). SQS 로 보내는 시험은 OutboxSqsFlowTest 가 따로 켠다.
        // 릴레이는 시험 도중 스스로 돌지 않게 주기를 길게 둔다 — 릴레이 동작은 common:outbox 시험이 본다
        "nova.outbox.transport=log",
        "nova.outbox.log-transport-allowed=true",
        "nova.outbox.relay-interval=1h",
        "management.server.port=0"
})
@Import({MySqlContainerConfig.class, RedisContainerConfig.class, SecurityTestConfig.class})
@DisplayName("헬스 체크 — 관리 포트의 readiness · liveness 는 토큰 없이 200, 서비스 포트로는 익명에게 열리지 않는다")
class HealthEndpointTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}") int port;
    @Value("${local.management.port}") int managementPort;
    @Autowired HealthEndpointGroups groups;

    @Test
    @DisplayName("관리 포트의 readiness · liveness 는 토큰 없이 200 UP")
    void probesAnswerOnTheManagementPortWithoutToken() throws Exception {
        assertThat(managementPort).isNotEqualTo(port);
        for (String path : new String[]{"/actuator/health/readiness", "/actuator/health/liveness"}) {
            HttpResponse<String> response = get(managementPort, path);
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).as(path).isEqualTo("{\"status\":\"UP\"}");
        }
    }

    @Test
    @DisplayName("서비스 포트(ALB 가 요청을 보내는 곳)로는 익명에게 health 가 열리지 않고, 관리 포트도 health 밖은 막힌다")
    void healthIsNotOpenToAnonymousOnTheServicePort() throws Exception {
        assertThat(get(port, "/actuator/health/readiness").statusCode()).isEqualTo(401);
        assertThat(get(port, "/actuator/health").statusCode()).isEqualTo(401);
        assertThat(get(managementPort, "/actuator/env").statusCode()).isEqualTo(401);
        assertThat(get(managementPort, "/actuator").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("readiness 는 앱의 준비 상태만 본다 — DB · Redis 는 넣지 않는다(공용 의존성 장애 한 번에 모든 태스크가 비정상이 되지 않게)")
    void readinessLeavesSharedDependenciesOut() {
        assertThat(groups.getPrimary().isMember("db")).as("대조군 — DB 기여자의 이름은 db").isTrue();
        assertThat(groups.getPrimary().isMember("redis")).as("대조군 — Redis 기여자의 이름은 redis").isTrue();
        assertThat(groups.get("readiness").isMember("readinessState")).isTrue();
        assertThat(groups.get("readiness").isMember("db")).isFalse();
        assertThat(groups.get("readiness").isMember("redis")).isFalse();
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
