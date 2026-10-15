package com.grandis.nova.member.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.member.support.MemberIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 새 알림이 열린 연결로 가는 길 — 저장한 인스턴스와 상관없이 연결을 가진 인스턴스가 DB 에서 찾아 보낸다(실제 Tomcat · MySQL).
 * 다른 인스턴스가 넣은 행은 표에 바로 넣어 흉내 낸다. "다시 보내지 않는다" 는 시간을 기다려 보지 않는다 — 주기를 직접 몇 번 돌린 뒤 새 행 하나를 넣어
 * 다음 이벤트가 그 수인지 본다(연결마다 쓰기 순서가 지켜져 중복이 있었다면 그 앞에 온다). 설정은 NotificationStreamTest 와 같아 컨텍스트를 함께 쓴다.
 */
@MemberIntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "management.server.port=0",
        "member.notification.stream.heartbeat=300ms",
        "member.notification.stream.max-connections=20",
        "member.notification.stream.tail-interval=200ms"
})
@DisplayName("새 알림을 연결된 회원에게 — DB 를 주기적으로 읽어 안 읽은 수를 보낸다 (실제 Tomcat · MySQL)")
class NotificationStreamPushTest {

    static final Duration WAIT = Duration.ofSeconds(5);

    @Value("${local.server.port}") int port;
    @Autowired JwtTokenProvider provider;
    @Autowired NotificationReceiver receiver;
    @Autowired NotificationStreams streams;
    @Autowired NotificationTail tail;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meterRegistry;

    final List<SseClient> clients = new ArrayList<>();

    @AfterEach
    void closeAll() {
        clients.forEach(SseClient::close);
        await().atMost(WAIT).until(() -> streams.size() == 0);
    }

    @Test
    @DisplayName("소비기 경로로 저장한 알림이 몇 초 안에 새 안 읽은 수로 온다")
    void storedNotificationArrives() throws Exception {
        UUID me = UUID.randomUUID();
        SseClient client = open(me);

        receiver.receive(new NotificationMessages(me).body());

        assertThat(client.nextEvent(WAIT)).isEqualTo(unread(1));
    }

    @Test
    @DisplayName("다른 인스턴스가 넣은 행(표에 바로 넣기)도 온다")
    void rowFromAnotherInstanceArrives() throws Exception {
        UUID me = UUID.randomUUID();
        SseClient client = open(me);

        insert(List.of(newId()), List.of(me));

        assertThat(client.nextEvent(WAIT)).isEqualTo(unread(1));
    }

    @Test
    @DisplayName("앞 주기보다 조금 오래된 id(겹침 창 안)로 늦게 커밋된 행도 오고, 창 안에서 다시 읽어도 한 번만 온다")
    void lateCommittedOlderIdArrivesExactlyOnce() throws Exception {
        UUID me = UUID.randomUUID();
        SseClient client = open(me);
        insert(List.of(newId()), List.of(me));
        assertThat(client.nextEvent(WAIT)).isEqualTo(unread(1));

        // 5초 전에 만든 id 가 이제야 커밋된 것처럼 넣는다
        insert(List.of(NotificationTailTest.v7At(Instant.now().minusSeconds(5))), List.of(me));
        assertThat(client.nextEvent(WAIT)).isEqualTo(unread(2));
        for (int i = 0; i < 3; i++) {
            tail.tick();   // 두 행 모두 창 안에서 다시 읽힌다
        }
        insert(List.of(newId()), List.of(me));

        assertThat(client.nextEvent(WAIT)).as("다시 보낸 2 가 없다").isEqualTo(unread(3));
    }

    @Test
    @DisplayName("연결이 없으면 주기가 DB 를 읽지 않는다")
    void noQueryWithoutConnections() {
        long before = meterRegistry.timer(NotificationTail.TIMER).count();

        tail.tick();

        assertThat(meterRegistry.timer(NotificationTail.TIMER).count()).isEqualTo(before);
    }

    @Test
    @DisplayName("1,500행이 한꺼번에 들어와도 쪽을 넘겨 끝까지 읽고, 이 인스턴스에 연결된 회원에게만 한 번씩 보낸다")
    void burstPagesAndPushesOnlyToConnected() throws Exception {
        List<UUID> customers = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            customers.add(UUID.randomUUID());
        }
        // 연결할 셋은 마지막 쪽(가장 큰 id)에 둔다 — 첫 쪽만 읽고 멈추면 이 셋에 닿지 않는다
        List<UUID> connected = customers.subList(297, 300);
        // 다른 시험의 연결은 모두 닫혔다(@AfterEach) — 보낸 수는 이 셋의 것만 오른다
        double sentBefore = sent();
        List<SseClient> mine = new ArrayList<>();
        for (UUID customer : connected) {
            mine.add(open(customer));
        }
        List<UUID> ids = new ArrayList<>();
        List<UUID> owners = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            ids.add(newId());
            owners.add(customers.get(i / 5));
        }
        ids.sort(NotificationTailTest::compare);

        insert(ids, owners);   // INSERT 한 문장 — 1,500행이 한 번에 보인다

        for (SseClient client : mine) {
            assertThat(client.nextEvent(WAIT)).isEqualTo(unread(5));
        }
        tail.tick();
        tail.tick();
        insert(connected.stream().map(c -> newId()).toList(), connected);
        for (SseClient client : mine) {
            assertThat(client.nextEvent(WAIT)).as("다시 보낸 5 가 없다").isEqualTo(unread(6));
        }
        // 첫 수 3 · 몰린 행 3 · 확인용 행 3
        await().atMost(WAIT).until(() -> sent() - sentBefore == 9);
    }

    /** 연결하고 첫 수(0)를 받는다. */
    private SseClient open(UUID customerId) throws InterruptedException {
        SseClient client = SseClient.open(port, provider.create(customerId.toString(), Role.USER, UUID.randomUUID(), TokenType.ACCESS));
        clients.add(client);
        assertThat(client.nextEvent(WAIT)).isEqualTo(unread(0));
        return client;
    }

    private double sent() {
        return meterRegistry.counter(NotificationStreams.PUSH_METRIC, "outcome", "sent").count();
    }

    private static SseClient.Event unread(long count) {
        return new SseClient.Event("unread", "{\"count\":" + count + "}");
    }

    private static UUID newId() {
        return UuidVersion7Strategy.INSTANCE.generateUuid(null);
    }

    /** 다른 인스턴스의 저장을 흉내 낸다 — 여러 행 INSERT 한 문장. */
    private void insert(List<UUID> ids, List<UUID> customers) {
        String row = "(?, ?, ?, 'TEST', '제목', '본문', 'PREORDER', 'x', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))";
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            args.add(UuidBinary.toBytes(ids.get(i)));
            args.add(UUID.randomUUID().toString());
            args.add(UuidBinary.toBytes(customers.get(i)));
        }
        jdbc.update("INSERT INTO notifications (id, event_id, customer_id, kind, title, body, target_type, target_id, occurred_at, created_at) VALUES "
                + String.join(", ", Collections.nCopies(ids.size(), row)), args.toArray());
    }
}
