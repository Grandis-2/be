package com.grandis.nova.preorder.outbox.publish;

import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.preorder.accept.RegisterJobReady;
import com.grandis.nova.preorder.outbox.OutboxEvent;
import com.grandis.nova.preorder.outbox.OutboxEventRepository;
import com.grandis.nova.preorder.outbox.OutboxWriter;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;

/**
 * 커밋 직후 발행 · 실패 시 릴레이 재발행. 전송 구현은 받은 메시지를 모으는 대역이다.
 * DB 를 공유하므로 단정은 이 테스트가 만든 행으로만 한다.
 */
@PreorderIntegrationTest
class OutboxPublishTest {

    static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    OutboxWriter writer;

    @Autowired
    OutboxRelay relay;

    @Autowired
    OutboxEventRepository outboxEvents;

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    OutboxProperties properties;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @MockitoBean
    MessageTransport transport;

    /** 전송 구현이 받은 메시지. 여러 스레드가 동시에 넣는다. */
    final Queue<OutboundMessage> sent = new ConcurrentLinkedQueue<>();

    /** 메시지를 보낸 스레드(eventId 별). */
    final Map<String, Thread> senders = new ConcurrentHashMap<>();

    /** true 면 전송이 실패한다. */
    volatile boolean transportDown;

    /** 이 eventId 를 보낼 때 released 가 열릴 때까지 멈춘다. */
    volatile String blockedEventId;
    final CountDownLatch sending = new CountDownLatch(1);
    final CountDownLatch released = new CountDownLatch(1);

    long aggregateId;

    @BeforeEach
    void setUp() {
        aggregateId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        willAnswer(invocation -> {
            if (transportDown) {
                throw new IllegalStateException("transport down");
            }
            OutboundMessage message = invocation.getArgument(0);
            if (message.eventId().equals(blockedEventId)) {
                sending.countDown();
                released.await(ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            }
            senders.put(message.eventId(), Thread.currentThread());
            sent.add(message);
            return null;
        }).given(transport).send(any());
    }

    @Test
    void 커밋되면_곧바로_가상_스레드에서_봉투로_발행하고_발행_완료를_남긴다() {
        OutboxEvent event = transactionTemplate.execute(status ->
                writer.append(new RegisterJobReady(aggregateId, "9f1c2d3e")));

        await().atMost(ASYNC_TIMEOUT).until(() -> publishedAt(event.getId()) != null);

        OutboundMessage message = sentOf(event.getEventId()).getFirst();
        assertThat(senders.get(event.getEventId()).isVirtual()).isTrue();
        assertThat(message.destination()).isEqualTo("preorder-register");
        assertThat(message.eventType()).isEqualTo("REGISTER_JOB_READY");
        JsonNode body = jsonMapper.readTree(message.body());
        assertThat(body.get("eventId").asString()).isEqualTo(event.getEventId());
        assertThat(body.get("aggregateType").asString()).isEqualTo("PREORDER_SYNC_JOB");
        assertThat(body.get("aggregateId").asLong()).isEqualTo(aggregateId);
        assertThat(body.get("occurredAt").isString()).isTrue();
        assertThat(body.get("payload").get("preorderId").asString()).isEqualTo("9f1c2d3e");
    }

    @Test
    void 롤백되면_발행하지_않는다() {
        OutboxEvent event = transactionTemplate.execute(status -> {
            OutboxEvent appended = writer.append(new RegisterJobReady(aggregateId, "9f1c2d3e"));
            status.setRollbackOnly();
            return appended;
        });

        // 커밋 후 콜백 자체가 불리지 않으므로 잠시 기다려도 나가는 것이 없어야 한다
        await().during(Duration.ofMillis(300)).atMost(ASYNC_TIMEOUT)
                .until(() -> sentOf(event.getEventId()).isEmpty());
    }

    @Test
    void 전송이_실패하면_미발행으로_남고_릴레이가_1분_뒤_다시_보낸다() {
        transportDown = true;
        OutboxEvent event = transactionTemplate.execute(status ->
                writer.append(new RegisterJobReady(aggregateId, "9f1c2d3e")));
        await().atMost(ASYNC_TIMEOUT).until(() -> attempts(event.getId()) == 1);
        assertThat(publishedAt(event.getId())).isNull();

        transportDown = false;
        relay.relay();
        assertThat(publishedAt(event.getId())).as("1분이 지나지 않은 행은 커밋 직후 발행 몫").isNull();

        age(event.getId());
        relay.relay();

        assertThat(publishedAt(event.getId())).isNotNull();
        assertThat(sentOf(event.getEventId())).hasSize(1);
    }

    @Test
    void 릴레이는_다른_서비스가_적은_종류는_보내지_않는다() {
        Long foreign = insertUnpublished("EXTERNAL_JOB_SUCCEEDED");

        relay.relay();

        assertThat(publishedAt(foreign)).isNull();
    }

    @Test
    void 여러_인스턴스가_동시에_릴레이해도_한_행은_한_번만_보낸다() throws Exception {
        List<String> eventIds = IntStream.range(0, 30)
                .mapToObj(i -> insertUnpublished("CANCEL_JOB_READY"))
                .map(this::eventIdOf)
                .toList();

        List<Outcome<Integer>> outcomes = Concurrently.run(4, i -> () -> relay.relay());

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(eventIds).allSatisfy(eventId -> assertThat(sentOf(eventId)).hasSize(1));
    }

    @Test
    void 보내는_동안_행_잠금을_쥐지_않고_리스만_건다() throws Exception {
        Long id = insertUnpublished("CANCEL_JOB_READY");
        blockedEventId = eventIdOf(id);

        CompletableFuture<Integer> relaying = CompletableFuture.supplyAsync(relay::relay);
        assertThat(sending.await(ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        Long locked = transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
                "SELECT id FROM outbox_events WHERE id = ? FOR UPDATE NOWAIT", Long.class, id));
        assertThat(locked).as("다른 트랜잭션이 기다리지 않고 잠근다").isEqualTo(id);
        assertThat(leaseUntil(id)).isNotNull();

        released.countDown();
        relaying.get(ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(publishedAt(id)).isNotNull();
    }

    @Test
    void 리스_중인_행은_건너뛰고_리스가_끝나면_다시_가져간다() {
        Long id = insertUnpublished("CANCEL_JOB_READY");
        jdbcTemplate.update(
                "UPDATE outbox_events SET lease_until = UTC_TIMESTAMP(6) + INTERVAL 5 MINUTE WHERE id = ?", id);

        relay.relay();
        assertThat(sentOf(eventIdOf(id))).as("다른 인스턴스가 보내는 중").isEmpty();

        jdbcTemplate.update(
                "UPDATE outbox_events SET lease_until = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", id);
        relay.relay();

        assertThat(publishedAt(id)).as("보내던 인스턴스가 죽어 리스가 끝났다").isNotNull();
        assertThat(sentOf(eventIdOf(id))).hasSize(1);
    }

    @Test
    void 릴레이_전송이_실패하면_시도_횟수를_올리고_리스를_풀어_다음_주기에_다시_보낸다() {
        Long id = insertUnpublished("CANCEL_JOB_READY");
        transportDown = true;

        relay.relay();

        assertThat(attempts(id)).isEqualTo(1);
        assertThat(leaseUntil(id)).isNull();
        assertThat(publishedAt(id)).isNull();

        transportDown = false;
        relay.relay();
        assertThat(publishedAt(id)).isNotNull();
    }

    @Test
    void 보내기_전에_리스가_끝나_다른_인스턴스가_가져간_행은_보내지_않는다() {
        Long id = insertUnpublished("CANCEL_JOB_READY");
        // 가져간 뒤 보내기 직전(두 번째로 시각을 읽을 때), 다른 인스턴스가 리스를 새로 건 것으로 만든다
        Clock takenOver = new Clock() {
            private int reads;

            @Override
            public ZoneId getZone() {
                return Clock.systemUTC().getZone();
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                if (++reads == 2) {
                    jdbcTemplate.update("UPDATE outbox_events SET lease_until = UTC_TIMESTAMP(6) + INTERVAL 5 MINUTE"
                            + " WHERE id = ?", id);
                }
                return Instant.now().truncatedTo(ChronoUnit.MICROS);
            }
        };

        new OutboxRelay(outboxEvents, publisher, properties, transactionTemplate, takenOver).relay();

        assertThat(sentOf(eventIdOf(id))).isEmpty();
        assertThat(publishedAt(id)).isNull();
    }

    @Test
    void 남의_리스는_연장하지도_늦게_실패한_쪽이_풀지도_않는다() {
        Long id = insertUnpublished("CANCEL_JOB_READY");
        Instant mine = Instant.parse("2026-01-01T00:00:00Z");
        jdbcTemplate.update(
                "UPDATE outbox_events SET lease_until = UTC_TIMESTAMP(6) + INTERVAL 5 MINUTE WHERE id = ?", id);
        Object theirs = leaseUntil(id);

        assertThat(outboxEvents.renewLease(id, mine, Instant.now())).isZero();
        outboxEvents.recordFailure(id, mine);

        assertThat(leaseUntil(id)).isEqualTo(theirs);
        assertThat(attempts(id)).as("실패 횟수는 센다").isEqualTo(1);
    }

    /** 커밋 직후 발행을 거치지 않은 미발행 행. 만든 지 1분이 지난 것으로 둔다. */
    private Long insertUnpublished(String eventType) {
        String eventId = ShopFixtures.unique();
        jdbcTemplate.update("""
                INSERT INTO outbox_events (event_id, aggregate_type, aggregate_id, event_type, payload, created_at)
                VALUES (?, 'PREORDER_SYNC_JOB', ?, ?, '{}', UTC_TIMESTAMP(6) - INTERVAL 2 MINUTE)
                """, eventId, aggregateId, eventType);
        return jdbcTemplate.queryForObject("SELECT id FROM outbox_events WHERE event_id = ?", Long.class, eventId);
    }

    private void age(Long id) {
        jdbcTemplate.update("UPDATE outbox_events SET created_at = created_at - INTERVAL 2 MINUTE WHERE id = ?", id);
    }

    private Object publishedAt(Long id) {
        return row(id).get("published_at");
    }

    private Object leaseUntil(Long id) {
        return row(id).get("lease_until");
    }

    private int attempts(Long id) {
        return ((Number) row(id).get("publish_attempts")).intValue();
    }

    private String eventIdOf(Long id) {
        return (String) row(id).get("event_id");
    }

    private Map<String, Object> row(Long id) {
        return jdbcTemplate.queryForMap(
                "SELECT event_id, publish_attempts, lease_until, published_at FROM outbox_events WHERE id = ?", id);
    }

    private List<OutboundMessage> sentOf(String eventId) {
        return sent.stream().filter(message -> message.eventId().equals(eventId)).toList();
    }
}
