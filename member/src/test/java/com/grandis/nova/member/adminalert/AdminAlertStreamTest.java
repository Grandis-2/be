package com.grandis.nova.member.adminalert;

import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.member.support.MemberIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * 관리자 알림 실시간 연결 — 실제 Tomcat 위에서 화면처럼 Bearer 헤더 · Accept: text/event-stream 으로 붙는다(회원 알림의 실시간 연결 시험과 같은 방식).
 * MockMvc 는 비동기 디스패치 · 연결 끊김 · 주기 신호를 끝까지 보지 못해 진짜 HTTP 로 본다.
 */
@MemberIntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "management.server.port=0",
        "member.admin-alert.stream.heartbeat=300ms",
        "member.admin-alert.stream.max-per-principal=2",
        "member.admin-alert.stream.max-connections=3",
        "member.admin-alert.stream.tail-interval=200ms"
})
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("관리자 알림 실시간 연결 — GET /api/v1/admin/notifications/stream (실제 Tomcat · MySQL)")
class AdminAlertStreamTest {

    static final String PATH = "/api/v1/admin/notifications/stream";
    static final Duration WAIT = Duration.ofSeconds(5);
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Value("${local.server.port}") int port;
    @Autowired JwtTokenProvider provider;
    @Autowired GrafanaAlertReceiver grafana;
    @MockitoSpyBean AdminAlertStreams streams;
    @Autowired JdbcTemplate jdbc;

    final List<SseClient> clients = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM admin_alerts");
    }

    /** 닫은 클라이언트는 서버가 다음 신호를 보낼 때 알아챈다 — 연결 수를 세는 시험이 앞 시험의 연결을 세지 않게 비워질 때까지 기다린다. */
    @AfterEach
    void closeAll() {
        clients.forEach(SseClient::close);
        await().atMost(WAIT).until(() -> streams.size() == 0);
    }

    @Test
    @DisplayName("연결 뒤 한 주기 안에 unread 가 오고, 새 알림 · 해소가 notification 으로 온 뒤 unread 가 따라온다")
    void pushesChanges() throws Exception {
        SseClient client = open("admin", PATH);
        assertThat(client.status()).isEqualTo(200);
        assertThat(client.header("Cache-Control")).contains("no-cache");
        SseClient.Event first = client.nextEvent(WAIT);
        assertThat(first.name()).isEqualTo("unread");
        assertThat(JSON.readTree(first.data()).get("ADMIN").get("urgent").asLong()).isZero();

        String startsAt = "2026-10-26T05:00:00Z";
        grafana.receive(AdminAlertMessages.Grafana.firing("REGISTER_FAILED", startsAt).metric("deadLetters", "3").body());
        JsonNode created = notification(client);
        assertThat(created.get("type").asString()).isEqualTo("REGISTER_FAILED");
        assertThat(created.get("status").asString()).isEqualTo("OPEN");
        SseClient.Event unread = client.nextEvent(WAIT);
        assertThat(unread.name()).isEqualTo("unread");
        assertThat(JSON.readTree(unread.data()).get("ADMIN").get("urgent").asLong()).isEqualTo(1);

        grafana.receive(AdminAlertMessages.Grafana.resolved("REGISTER_FAILED", startsAt, "2026-10-26T05:03:00Z").body());
        JsonNode resolved = notification(client);
        assertThat(resolved.get("notificationId").asString()).isEqualTo(created.get("notificationId").asString());
        assertThat(resolved.get("status").asString()).isEqualTo("RESOLVED");
    }

    @Test
    @DisplayName("audience 를 주면 그 탭의 항목만 오고, unread 는 둘 다 담는다. 같은 변경을 두 번 보내지 않는다")
    void filtersByAudience() throws Exception {
        SseClient developer = open("admin", PATH + "?audience=DEVELOPER");
        assertThat(developer.nextEvent(WAIT).name()).isEqualTo("unread");

        grafana.receive(AdminAlertMessages.Grafana.firing("SHIPPING_BACKLOG", "2026-10-26T05:00:00Z").metric("total", "58").body());
        SseClient.Event onlyUnread = developer.nextEvent(WAIT);
        assertThat(onlyUnread.name()).isEqualTo("unread");
        assertThat(JSON.readTree(onlyUnread.data()).get("ADMIN").get("info").asLong()).isEqualTo(1);

        grafana.receive(AdminAlertMessages.Grafana.firing("SERVICE_ERROR_SPIKE", "2026-10-26T05:00:00Z").target("SERVICE", "order").body());
        assertThat(notification(developer).get("audience").asString()).isEqualTo("DEVELOPER");
        assertThat(developer.nextEvent(WAIT).name()).isEqualTo("unread");
        assertThat(developer.nextEvent(Duration.ofSeconds(1))).as("같은 변경은 다시 오지 않는다").isNull();
    }

    @Test
    @DisplayName("연결이 있으면 주기 신호(SSE 주석)를 보낸다")
    void heartbeat() throws Exception {
        SseClient client = open("admin", PATH);
        assertThat(client.nextEvent(WAIT).name()).isEqualTo("unread");

        SseClient.Event beat = client.next(WAIT);

        assertThat(beat).isNotNull();
        assertThat(beat.comment()).isTrue();
    }

    @Test
    @DisplayName("토큰 만료가 최대 수명보다 이르면 그때 서버가 닫는다 — 화면은 새 토큰으로 다시 연결한다")
    void closesWhenTokenExpires() throws Exception {
        String shortLived = provider.create("admin", Role.ADMIN, UUID.randomUUID(), TokenType.ACCESS, Instant.now().plusSeconds(3));
        SseClient client = track(SseClient.open(port, PATH, shortLived));
        assertThat(client.nextEvent(WAIT).name()).isEqualTo("unread");

        assertThat(client.awaitEnd(Duration.ofSeconds(6))).isTrue();
        await().atMost(WAIT).until(() -> streams.size() == 0);
    }

    @Test
    @DisplayName("같은 계정의 연결이 상한(시험 2)을 넘으면 가장 오래된 연결을 닫는다")
    void samePrincipalReplacesTheOldest() throws Exception {
        SseClient oldest = open("admin", PATH);
        assertThat(oldest.nextEvent(WAIT).name()).isEqualTo("unread");
        SseClient second = open("admin", PATH);
        assertThat(second.nextEvent(WAIT).name()).isEqualTo("unread");

        SseClient third = open("admin", PATH);

        assertThat(third.nextEvent(WAIT).name()).isEqualTo("unread");
        SseClient.Event replaced = oldest.nextEvent(WAIT);
        assertThat(replaced.name()).as("닫기 전에 사유를 보낸다 — 화면은 다시 연결하지 않는다").isEqualTo("replaced");
        assertThat(JSON.readTree(replaced.data()).get("reason").asString()).isEqualTo("MAX_PER_PRINCIPAL");
        assertThat(oldest.awaitEnd(WAIT)).isTrue();
        assertThat(streams.size()).isEqualTo(2);
        assertThat(second.next(Duration.ofSeconds(1))).as("남은 연결은 계속 신호를 받는다").isNotEqualTo(SseClient.END).isNotNull();
    }

    @Test
    @DisplayName("회원(USER) 토큰은 403, 인스턴스 상한을 넘으면 503, 형식이 틀린 audience 는 400 — 모두 JSON 봉투")
    void rejects() throws Exception {
        String user = provider.create(UUID.randomUUID().toString(), Role.USER, UUID.randomUUID(), TokenType.ACCESS);
        SseClient forbidden = track(SseClient.open(port, PATH, user));
        assertThat(forbidden.status()).isEqualTo(403);
        assertThat(forbidden.header("Content-Type")).startsWith("application/json");

        SseClient badAudience = open("admin", PATH + "?audience=FOO");
        assertThat(badAudience.status()).isEqualTo(400);
        assertThat(badAudience.header("Content-Type")).startsWith("application/json");
        assertThat(JSON.readTree(badAudience.errorBody).get("error").get("code").asString()).isEqualTo("VALIDATION_FAILED");

        for (String principal : List.of("admin", "ops-1", "ops-2")) {
            assertThat(open(principal, PATH).status()).isEqualTo(200);
        }
        SseClient full = open("ops-3", PATH);
        assertThat(full.status()).isEqualTo(503);
        assertThat(full.header("Content-Type")).startsWith("application/json");
        assertThat(JSON.readTree(full.errorBody).get("error").get("code").asString()).isEqualTo("DEPENDENCY_UNAVAILABLE");
    }

    @Test
    @DisplayName("응답 전의 예기치 않은 예외는 500 · JSON · 일반 문구다 — 예외 메시지를 내보내지 않는다")
    void unexpectedFailureBeforeResponseIs500Json() throws Exception {
        doThrow(new IllegalStateException("내부 상태 깨짐")).when(streams).open(eq("boom"), any(), any());

        SseClient failed = open("boom", PATH);

        assertThat(failed.status()).isEqualTo(500);
        assertThat(failed.header("Content-Type")).startsWith("application/json");
        JsonNode error = JSON.readTree(failed.errorBody).get("error");
        assertThat(error.get("code").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(failed.errorBody).doesNotContain("내부 상태 깨짐");
    }

    @Test
    @DisplayName("응답이 시작된 뒤의 예외는 스트림만 끝내고 JSON 봉투를 덧붙이지 않으며, WARN 한 줄로 남는다(Tomcat ERROR 없음)")
    void failureAfterResponseStartedEndsQuietly(CapturedOutput output) throws Exception {
        AtomicReference<SseEmitter> emitter = new AtomicReference<>();
        doAnswer(call -> {
            SseEmitter opened = (SseEmitter) call.callRealMethod();
            emitter.set(opened);
            return opened;
        }).when(streams).open(eq("crash"), any(), any());
        SseClient client = open("crash", PATH);
        assertThat(client.nextEvent(WAIT).name()).isEqualTo("unread");

        emitter.get().completeWithError(new IllegalStateException("쓰기 뒤 실패"));

        assertThat(client.awaitEnd(WAIT)).isTrue();
        assertThat(client.lines).noneMatch(line -> line.contains("\"success\""));
        await().atMost(WAIT).until(() -> output.getOut().contains("응답을 시작한 뒤 실패해 끝낸다"));
        assertThat(output.getAll()).doesNotContain("Servlet.service()");
    }

    private SseClient open(String principal, String path) {
        return track(SseClient.open(port, path, provider.create(principal, Role.ADMIN, UUID.randomUUID(), TokenType.ACCESS)));
    }

    private SseClient track(SseClient client) {
        clients.add(client);
        return client;
    }

    private static JsonNode notification(SseClient client) throws InterruptedException {
        SseClient.Event event = client.nextEvent(WAIT);
        assertThat(event).isNotNull();
        assertThat(event.name()).isEqualTo("notification");
        return JSON.readTree(event.data());
    }
}
