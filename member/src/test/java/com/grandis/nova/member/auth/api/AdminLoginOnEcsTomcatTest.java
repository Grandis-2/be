package com.grandis.nova.member.auth.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.common.security.AuthRedisKeys;
import com.grandis.nova.member.MemberApplication;
import com.grandis.nova.member.support.MemberTestContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 실제 Tomcat 에서, ECS 로 감지된 상태(`AWS_EXECUTION_ENV=AWS_ECS_FARGATE`) + 예시 설정대로 `forward-headers-strategy=none`.
 * MockMvc 는 Tomcat 의 RemoteIpValve 를 거치지 않아 이 경우를 못 본다 — 그래서 진짜 HTTP 로 보낸다.
 * 설정이 빠지면 Boot 가 밸브를 켜고 헤더를 고쳐 써서 위조한 값이나 엣지 IP 로 셌다(리뷰 실측). 지금은 가드가 그 설정으로는 기동을 막는다.
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
        "spring.data.redis.timeout=300ms",
        "spring.data.redis.connect-timeout=200ms",
        "member.refresh-cleanup.cron=-",
        "AWS_EXECUTION_ENV=AWS_ECS_FARGATE",
        "server.forward-headers-strategy=none",
        // 실제 서버를 띄우므로 관리 포트(기본 9080, MemberDefaults)도 열린다 — 고정 포트가 이미 쓰이고 있으면 기동이 실패하니 빈 포트로
        "management.server.port=0"
})
@Import(MemberTestContext.class)
@DisplayName("ECS 의 실제 Tomcat — X-Forwarded-For 를 받은 그대로 세어 실제 접속 IP 로 센다")
class AdminLoginOnEcsTomcatTest {

    @Value("${local.server.port}") int port;
    @Autowired StringRedisTemplate redis;

    @Test
    @DisplayName("위조 칸이 있든 없든 CloudFront 가 붙인 칸(오른쪽 두 번째)으로 센다 — 위조 값 · 엣지 IP 로 세지 않는다")
    void countsTheViewerIpOnRealTomcat() throws Exception {
        String viewer = "203.0.113." + java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 255);
        String other = "198.51.100." + java.util.concurrent.ThreadLocalRandom.current().nextInt(1, 255);
        assertThat(login("6.6.6.6, " + viewer + ", 130.176.0.1")).isEqualTo(401);
        assertThat(login(other + ", 130.176.0.1")).as("클라이언트가 헤더를 안 보낸 흔한 모양 — 두 칸").isEqualTo(401);

        assertThat(redis.hasKey(AuthRedisKeys.adminLoginAttempts(viewer))).isTrue();
        assertThat(redis.hasKey(AuthRedisKeys.adminLoginAttempts(other))).isTrue();
        assertThat(redis.hasKey(AuthRedisKeys.adminLoginAttempts("6.6.6.6"))).as("위조 값").isFalse();
        assertThat(redis.hasKey(AuthRedisKeys.adminLoginAttempts("130.176.0.1"))).as("엣지 IP").isFalse();
    }

    private int login(String forwardedFor) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/admin/session"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", forwardedFor)
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"admin\",\"password\":\"wrong-" + MemberTestContext.ADMIN_PASSWORD.length() + "\"}"))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
