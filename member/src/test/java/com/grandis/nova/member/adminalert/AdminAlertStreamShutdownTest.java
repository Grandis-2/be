package com.grandis.nova.member.adminalert;

import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.member.MemberApplication;
import com.grandis.nova.member.TestKeys;
import com.grandis.nova.member.support.Containers;
import com.grandis.nova.member.support.MemberIntegrationTest;
import com.grandis.nova.member.support.MemberTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 종료할 때 열린 관리자 알림 연결을 웹 서버의 정상 종료보다 먼저 모두 닫는다 — 정상 종료(요청을 30초까지 기다린다)가 스트림을 기다리지 않는다.
 * 컨텍스트를 직접 닫아야 해서 시험 컨텍스트 캐시 밖에서 member 를 띄운다(회원 알림의 종료 시험과 같은 방식). 소비기는 켜지 않는다.
 */
@DisplayName("관리자 알림 실시간 연결 — 종료하면 모두 닫는다 (실제 Tomcat)")
class AdminAlertStreamShutdownTest {

    @Test
    @DisplayName("컨텍스트를 닫으면 열린 연결이 모두 끝나고, 정상 종료가 스트림을 기다리지 않는다")
    void shutdownCompletesAllStreams() throws Exception {
        ConfigurableApplicationContext context = start();
        int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        JwtTokenProvider provider = context.getBean(JwtTokenProvider.class);
        List<SseClient> clients = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                SseClient client = SseClient.open(port, "/api/v1/admin/notifications/stream",
                        provider.create("admin", Role.ADMIN, UUID.randomUUID(), TokenType.ACCESS));
                assertThat(client.nextEvent(Duration.ofSeconds(5)).name()).isEqualTo("unread");
                clients.add(client);
            }

            long started = System.nanoTime();
            context.close();
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            assertThat(took).as("정상 종료가 열린 스트림을 기다리면 30초다").isLessThan(Duration.ofSeconds(10));
            for (SseClient client : clients) {
                assertThat(client.awaitEnd(Duration.ofSeconds(2))).isTrue();
            }
        } finally {
            clients.forEach(SseClient::close);
            context.close();
        }
    }

    private static ConfigurableApplicationContext start() {
        List<String> properties = new ArrayList<>(List.of(MemberIntegrationTest.class.getAnnotation(SpringBootTest.class).properties()));
        properties.addAll(List.of(Containers.datasourceProperties()));
        properties.add("spring.data.redis.host=" + Containers.redisHost());
        properties.add("spring.data.redis.port=" + Containers.redisPort());
        properties.add("admin.password-hash=" + new BCryptPasswordEncoder(12).encode(MemberTestContext.ADMIN_PASSWORD));
        TestKeys.register((name, value) -> properties.add(name + "=" + value.get()));
        properties.addAll(List.of("server.port=0", "management.server.port=0", "spring.main.banner-mode=off",
                "spring.lifecycle.timeout-per-shutdown-phase=30s"));
        return new SpringApplicationBuilder(MemberApplication.class).properties(properties.toArray(String[]::new)).run();
    }
}
