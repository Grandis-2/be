package com.grandis.nova.member.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.member.support.MemberIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 안 읽은 수 실시간 연결 — 실제 Tomcat 위에서 화면처럼 Bearer 헤더 · Accept: text/event-stream 으로 붙는다.
 * MockMvc 는 비동기 디스패치 · 연결 끊김 · 주기 신호를 끝까지 보지 못해 진짜 HTTP 로 본다. 회원 id 는 시험마다 새로 만든다 — 다른 시험의 알림이 섞이지 않게.
 */
@MemberIntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "management.server.port=0",
        // 주기 신호를 짧게 — 신호를 기다리는 시험과, 닫은 클라이언트를 서버가 알아채는 시간(보낼 때에야 드러난다)을 줄인다
        "member.notification.stream.heartbeat=300ms",
        "member.notification.stream.max-connections=20",
        // 첫 수가 한 주기 뒤에 온다 — 짧게
        "member.notification.stream.tail-interval=200ms"
})
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("안 읽은 수 실시간 연결 — GET /api/v1/notifications/stream (실제 Tomcat · MySQL)")
class NotificationStreamTest {

    static final Duration WAIT = Duration.ofSeconds(5);
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Value("${local.server.port}") int port;
    @Autowired JwtTokenProvider provider;
    @Autowired NotificationReceiver receiver;
    @Autowired NotificationStreams streams;
    @Autowired HikariDataSource dataSource;

    final List<SseClient> clients = new ArrayList<>();
    UUID me;

    @BeforeEach
    void setUp() {
        me = UUID.randomUUID();
    }

    /** 닫은 클라이언트는 서버가 다음 신호를 보낼 때 알아챈다 — 연결 수를 세는 시험이 앞 시험의 연결을 세지 않게 비워질 때까지 기다린다. */
    @AfterEach
    void closeAll() {
        clients.forEach(SseClient::close);
        await().atMost(WAIT).until(() -> streams.size() == 0);
    }

    @Test
    @DisplayName("첫 이벤트는 한 주기 안에 오는 지금 안 읽은 수다 — event: unread, data: {\"count\": n}, Cache-Control: no-cache")
    void firstEventIsCurrentUnreadCount() throws Exception {
        receiver.receive(new NotificationMessages(me).body());
        receiver.receive(new NotificationMessages(me, UUID.randomUUID()).body());

        SseClient client = open(me);

        assertThat(client.status()).isEqualTo(200);
        assertThat(client.header("Content-Type")).startsWith("text/event-stream");
        assertThat(client.header("Cache-Control")).isEqualTo("no-cache");
        assertThat(client.nextEvent(WAIT)).isEqualTo(new SseClient.Event("unread", "{\"count\":2}"));
    }

    @Test
    @DisplayName("회원 전용 — 토큰 없으면 401, ADMIN 토큰은 403. Accept 가 text/event-stream 이어도 오류는 봉투(JSON)다")
    void customerOnly() throws Exception {
        SseClient anonymous = track(SseClient.open(port, null));
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(errorCode(anonymous)).isEqualTo("UNAUTHENTICATED");

        SseClient admin = track(SseClient.open(port, provider.create("admin", Role.ADMIN, UUID.randomUUID(), TokenType.ACCESS)));
        assertThat(admin.status()).isEqualTo(403);
        assertThat(errorCode(admin)).isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("연결이 있으면 주기 신호(SSE 주석)를 보낸다")
    void heartbeat() throws Exception {
        SseClient client = open(me);
        assertThat(client.nextEvent(WAIT).name()).isEqualTo("unread");

        SseClient.Event beat = client.next(WAIT);

        assertThat(beat).isNotNull();
        assertThat(beat.comment()).isTrue();
    }

    @Test
    @DisplayName("토큰 만료가 최대 수명보다 이르면 그때 서버가 닫는다 — 화면은 새 토큰으로 다시 연결한다")
    void closesWhenTokenExpires() throws Exception {
        String shortLived = provider.create(me.toString(), Role.USER, UUID.randomUUID(), TokenType.ACCESS,
                Instant.now().plusSeconds(3));
        SseClient client = track(SseClient.open(port, shortLived));
        assertThat(client.nextEvent(WAIT).name()).isEqualTo("unread");

        assertThat(client.awaitEnd(Duration.ofSeconds(6))).isTrue();
        await().atMost(WAIT).until(() -> streams.size() == 0);
    }

    @Test
    @DisplayName("한 회원의 여섯 번째 연결은 가장 오래된 연결을 닫는다")
    void sixthConnectionClosesTheOldest() throws Exception {
        List<SseClient> mine = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            mine.add(open(me));
            assertThat(mine.getLast().nextEvent(WAIT).name()).isEqualTo("unread");
        }

        SseClient sixth = open(me);

        assertThat(sixth.nextEvent(WAIT).name()).isEqualTo("unread");
        assertThat(mine.getFirst().awaitEnd(WAIT)).isTrue();
        assertThat(streams.size()).isEqualTo(5);
        for (SseClient other : mine.subList(1, 5)) {
            assertThat(other.next(Duration.ofSeconds(1))).as("남은 연결은 계속 신호를 받는다").isNotEqualTo(SseClient.END).isNotNull();
        }
    }

    @Test
    @DisplayName("인스턴스 상한을 넘으면 503 봉투(DEPENDENCY_UNAVAILABLE) — 화면은 주기 조회로 돌아간다")
    void overInstanceCapIs503() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThat(open(UUID.randomUUID()).status()).isEqualTo(200);
        }

        SseClient over = track(SseClient.open(port, token(me)));

        assertThat(over.status()).isEqualTo(503);
        assertThat(errorCode(over)).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(streams.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("연결은 DB 연결을 잡지 않는다 — 풀 크기(10)보다 많이 열어도 다른 API 가 돌고 활성 연결은 0 이다")
    void streamsDoNotHoldDatabaseConnections() throws Exception {
        int pool = dataSource.getMaximumPoolSize();
        for (int i = 0; i < pool + 2; i++) {
            assertThat(open(UUID.randomUUID()).nextEvent(WAIT).name()).isEqualTo("unread");
        }

        // 수 세기가 잠깐 연결을 쓸 수 있어 0 이 되는 순간을 기다린다 — 연결마다 하나씩 잡았다면 풀이 꽉 찬 채(10) 머문다
        await().atMost(WAIT).until(() -> dataSource.getHikariPoolMXBean().getActiveConnections() == 0);
        HttpResponse<String> unread = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/notifications/unread-count"))
                .header("Authorization", "Bearer " + token(me)).timeout(WAIT).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(unread.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("서버가 연결을 닫을 때의 비동기 디스패치가 인가에 걸리지 않는다 — 401 · 403 · 오류 로그가 없다")
    void asyncDispatchOnCompletionIsNotDenied(CapturedOutput output) throws Exception {
        SseClient client = open(me);
        assertThat(client.nextEvent(WAIT).name()).isEqualTo("unread");
        int before = output.length();

        // 여섯 번 더 열어 첫 연결을 서버가 닫게 한다(complete → ASYNC 디스패치)
        for (int i = 0; i < 5; i++) {
            open(me);
        }
        assertThat(client.awaitEnd(WAIT)).isTrue();

        // 재디스패치는 닫은 뒤 컨테이너 스레드에서 돈다 — 한동안 오류가 없는지 본다
        await().during(Duration.ofMillis(500)).atMost(WAIT).until(() ->
                !output.toString().substring(before).matches("(?s).*(Access Denied|already committed|ERROR|auth rejected).*"));
    }

    private SseClient open(UUID customerId) {
        return track(SseClient.open(port, token(customerId)));
    }

    private SseClient track(SseClient client) {
        clients.add(client);
        return client;
    }

    private String token(UUID customerId) {
        return provider.create(customerId.toString(), Role.USER, UUID.randomUUID(), TokenType.ACCESS);
    }

    private static String errorCode(SseClient client) {
        JsonNode body = JSON.readTree(client.errorBody);
        return body.get("error").get("code").asString();
    }
}
