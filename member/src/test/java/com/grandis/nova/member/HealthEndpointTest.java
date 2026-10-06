package com.grandis.nova.member;

import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.member.support.MemberTestContext;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 Tomcat 두 개(서비스 포트 · 관리 포트)에 HTTP 로 묻는다 — MockMvc 는 관리 포트의 자식 컨텍스트를 띄우지 않아 이 경우를 못 본다.
 * 관리 포트는 시험끼리 겹치지 않게 0(아무 빈 포트)으로 덮는다. 나머지는 MemberDefaults 의 기본값 그대로다.
 */
@SpringBootTest(classes = MemberApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
        "spring.flyway.enabled=false",
        "jwt.issuer=nova-test",
        "jwt.access-token-validity=30m",
        "jwt.refresh-token-validity=14d",
        "kakao.client-id=cid",
        "kakao.client-secret=csecret",
        "kakao.token-uri=https://kauth.kakao.com/oauth/token",
        "kakao.user-info-uri=https://kapi.kakao.com/v2/user/me",
        "kakao.allowed-redirect-uris=http://localhost:3000/login/kakao/callback",
        "auth.refresh.allowed-origins=http://localhost:3000",
        "admin.username=admin",
        "auth.cookie.secure=false",
        // 기본값(60s)으로는 Redis 가 죽은 갈래가 시험을 멈춰 세운다. 예시 설정과 같은 값으로 빨리 실패시킨다.
        "spring.data.redis.timeout=300ms",
        "spring.data.redis.connect-timeout=200ms",
        // 주기 정리는 끈다 — 시험이 넣은 만료 행을 도중에 지워 결과가 흔들리지 않게. 정리 시험은 run() 을 직접 부른다
        "member.refresh-cleanup.cron=-",
        // 관리자 로그인 합계 제한은 시험끼리 공유하는 키 하나라, 기본값(60)이면 여러 시험의 시도가 쌓여 엉뚱한 곳에서 429 가 난다.
        // 합계 시험은 이 한도 바로 아래로 키를 심어 확인한다(AdminLoginThrottleTest)
        "auth.admin-login-limit.max-total-attempts=100000",
        "management.server.port=0"
})
@Import(MemberTestContext.class)
@DisplayName("헬스 체크 — 관리 포트의 readiness · liveness 는 토큰 없이 200, 서비스 포트로는 actuator 가 나가지 않는다")
class HealthEndpointTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Value("${local.server.port}") int port;
    @Value("${local.management.port}") int managementPort;
    @Autowired HealthEndpointGroups groups;
    @Autowired JwtTokenProvider provider;

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
    @DisplayName("서비스 포트(ALB 가 요청을 보내는 곳)로는 actuator 가 나가지 않는다 — 익명은 401, 토큰이 있어도 404(매핑 없음). 관리 포트도 health 밖은 막힌다")
    void actuatorStaysOffTheServicePort() throws Exception {
        assertThat(get(port, "/actuator/health/readiness").statusCode()).isEqualTo(401);
        assertThat(get(port, "/actuator/health").statusCode()).isEqualTo(401);
        String admin = provider.create("admin", Role.ADMIN, UUID.randomUUID(), TokenType.ACCESS);
        assertThat(get(port, "/actuator/health/readiness", admin).statusCode()).as("인증을 통과해도 서비스 포트에는 actuator 매핑이 없다").isEqualTo(404);
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

    private static HttpResponse<String> get(int port, String path, String accessToken) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Authorization", "Bearer " + accessToken)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
