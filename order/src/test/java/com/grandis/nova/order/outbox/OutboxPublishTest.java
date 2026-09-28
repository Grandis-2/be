package com.grandis.nova.order.outbox;

import com.grandis.nova.order.outbox.PreorderOrderSettled.RejectReason;
import com.grandis.nova.order.support.Concurrently;
import com.grandis.nova.order.support.Concurrently.Outcome;
import com.grandis.nova.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mockingDetails;

/**
 * 커밋 직후 발행 · 실패 시 릴레이 재발행. 전송 구현은 받은 메시지를 모으는 대역이다.
 * 커밋 · 롤백을 직접 보려고 테스트 트랜잭션 대신 TransactionTemplate 을 쓴다.
 * DB 를 공유하므로 단정은 이 테스트가 만든 행으로만 한다(행마다 새 aggregate_id).
 *
 * common:outbox 이전 시: preorder outbox.publish.OutboxPublishTest 와 같은 시나리오다. 공통으로 옮길 때 한 벌로 합친다.
 */
@OrderIntegrationTest
class OutboxPublishTest {

    static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(5);

    @Autowired
    OutboxWriter writer;

    @Autowired
    OutboxPublisher publisher;

    /** 커밋 전에 불렸는지 보려고 감싼다. 동작과 리스너 등록은 그대로다. */
    @MockitoSpyBean
    OutboxAfterCommitPublisher afterCommit;

    @Autowired
    OutboxEventRepository outboxEvents;

    @Autowired
    OutboxProperties properties;

    @Autowired
    OutboxRelay relay;

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

    /** 있으면 전송이 이 문이 열릴 때까지 멈춘다. 멈춘 동안 릴레이는 잠근 행을 쥐고 있다. */
    volatile CountDownLatch sendGate;

    /** 전송이 문 앞에서 멈췄다는 신호. */
    final CountDownLatch sendBlocked = new CountDownLatch(1);

    long preorderInternalId;

    @BeforeEach
    void setUp() {
        preorderInternalId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        willAnswer(invocation -> {
            if (transportDown) {
                throw new IllegalStateException("transport down");
            }
            CountDownLatch gate = sendGate;
            if (gate != null) {
                sendBlocked.countDown();
                gate.await(10, TimeUnit.SECONDS);
            }
            OutboundMessage message = invocation.getArgument(0);
            senders.put(message.eventId(), Thread.currentThread());
            sent.add(message);
            return null;
        }).given(transport).send(any());
    }

    @Test
    void commitPublishesOnceOnVirtualThreadAndMarksPublished() {
        Long id = appendCommitted();
        String eventId = eventIdOf(id);

        await().atMost(ASYNC_TIMEOUT).until(() -> publishedAt(id) != null);

        assertThat(sentOf(eventId)).hasSize(1);
        OutboundMessage message = sentOf(eventId).getFirst();
        assertThat(senders.get(eventId).isVirtual()).isTrue();
        assertThat(message.destination()).isEqualTo("preorder-events");
        assertThat(message.eventType()).isEqualTo("PREORDER_ORDER_SETTLED");
        assertThat(attempts(id)).isZero();
    }

    /**
     * 발행은 커밋이 끝난 뒤에만 시작된다. 커밋 전(BEFORE_COMMIT)에 시작하면 다른 트랜잭션에서 아직 보이지 않는 행을 찾다가
     * 그냥 끝나, 행이 릴레이 몫으로 밀린다.
     *
     * 트랜잭션 리스너는 커밋 스레드에서 동기로 불린다. beforeCompletion 은 모든 beforeCommit 콜백 뒤 · 커밋 전에 불리므로
     * 거기서 리스너 호출 여부를 보면 기다리지 않고 판정된다. 리스너가 @Async 가 되면 이 검사는 아무것도 잡지 못한다.
     */
    @Test
    void listenerIsNotCalledBeforeCommit() {
        AtomicBoolean calledBeforeCommit = new AtomicBoolean();
        Long id = transactionTemplate.execute(status -> {
            Long appended = writer.append(settled());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCompletion() {
                    // 여기서 던지면 스프링이 삼키므로 결과만 남기고 커밋 뒤에 단정한다
                    calledBeforeCommit.set(onAppendedCalls(appended) > 0);
                }
            });
            return appended;
        });

        assertThat(calledBeforeCommit).as("커밋 전 리스너 호출").isFalse();
        await().atMost(ASYNC_TIMEOUT).until(() -> publishedAt(id) != null);
    }

    /**
     * 받는 쪽(preorder)은 봉투를 EventEnvelope 로 풀고 payload 를 JsonNode 로 받는다. 칸 이름이 어긋나거나 payload 가
     * 문자열로 두 번 싸이면 받는 쪽에서 풀리지 않는다.
     */
    @Test
    void envelopeCarriesRowAndPayloadAsObject() {
        Long id = appendCommitted();
        String eventId = eventIdOf(id);
        await().atMost(ASYNC_TIMEOUT).until(() -> publishedAt(id) != null);

        JsonNode body = jsonMapper.readTree(sentOf(eventId).getFirst().body());

        assertThat(body.propertyNames()).containsExactlyInAnyOrder(
                "eventId", "eventType", "aggregateType", "aggregateId", "occurredAt", "payload");
        assertThat(body.get("eventId").asString()).isEqualTo(eventId);
        assertThat(body.get("eventType").asString()).isEqualTo("PREORDER_ORDER_SETTLED");
        assertThat(body.get("aggregateType").asString()).isEqualTo("PREORDER");
        assertThat(body.get("aggregateId").asLong()).isEqualTo(preorderInternalId);
        assertThat(body.get("occurredAt").isString()).isTrue();
        assertThat(Instant.parse(body.get("occurredAt").asString())).isEqualTo(createdAt(id));

        JsonNode payload = body.get("payload");
        assertThat(payload.isObject()).as("payload 는 문자열이 아닌 JSON 객체").isTrue();
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder(
                "preorderId", "result", "reason", "cancelSequence");
        assertThat(payload.get("preorderId").asString()).isEqualTo("token-" + preorderInternalId);
        assertThat(payload.get("result").asString()).isEqualTo("REJECTED");
        assertThat(payload.get("reason").asString()).isEqualTo("SHIPPED");
        assertThat(payload.get("cancelSequence").asLong()).isEqualTo(7L);
    }

    @Test
    void rollbackPublishesNothing() {
        Long id = transactionTemplate.execute(status -> {
            Long appended = writer.append(settled());
            status.setRollbackOnly();
            return appended;
        });

        // 커밋 후 콜백 자체가 불리지 않으므로 잠시 기다려도 나가는 것이 없어야 한다
        await().during(Duration.ofMillis(300)).atMost(ASYNC_TIMEOUT)
                .until(() -> sent.stream().noneMatch(message -> aggregateIdOf(message) == preorderInternalId));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE id = ?", Integer.class, id))
                .isZero();
    }

    @Test
    void transportFailureLeavesRowUnpublishedAndRelayResendsAfterOneMinute() {
        transportDown = true;
        Long id = appendCommitted();
        String eventId = eventIdOf(id);
        await().atMost(ASYNC_TIMEOUT).until(() -> attempts(id) == 1);
        assertThat(publishedAt(id)).isNull();

        transportDown = false;
        relay.relay();
        assertThat(publishedAt(id)).as("1분이 지나지 않은 행은 커밋 직후 발행 몫").isNull();

        age(id);
        relay.relay();

        assertThat(publishedAt(id)).isNotNull();
        assertThat(sentOf(eventId)).hasSize(1);
        assertThat(attempts(id)).isEqualTo(1);
    }

    /**
     * 같은 표를 preorder 릴레이도 읽는다. 이쪽은 order 종류만 잠가야 한다. 필터가 빠지면 남의 행은 종류를 몰라 보내지 못하고
     * 실패로 세어진다 — 보내지 않았다는 것만으로는 드러나지 않으므로 남의 행의 publish_attempts 가 그대로인지 본다.
     */
    @Test
    void relaySkipsPreorderEventTypes() {
        Long own = insertUnpublished("PREORDER_ORDER_SETTLED", false);
        List<Long> foreign = List.of(
                insertUnpublished("REGISTER_JOB_READY", false),
                insertUnpublished("CANCEL_JOB_READY", false),
                insertUnpublished("PREORDER_CANCEL_REQUESTED", false));

        relay.relay();

        assertThat(publishedAt(own)).isNotNull();
        assertThat(foreign).allSatisfy(id -> {
            assertThat(publishedAt(id)).isNull();
            assertThat(attempts(id)).as("남의 행을 잠가 실패로 세지 않는다").isZero();
            assertThat(sentOf(eventIdOf(id))).isEmpty();
        });
    }

    /**
     * 남의 미발행 행이 한 번에 잠그는 수(relayBatch)보다 많이 앞에 쌓여도 자기 행은 나간다.
     * 종류 필터가 없으면 id 순 LIMIT 이 남의 행으로 채워져 자기 행이 영원히 밀린다.
     */
    @Test
    void ownRowIsRelayedEvenWhenForeignRowsFillTheBatch() {
        IntStream.rangeClosed(0, properties.relayBatch())
                .forEach(i -> insertUnpublished("REGISTER_JOB_READY", false));
        Long own = insertUnpublished("PREORDER_ORDER_SETTLED", false);

        relay.relay();

        assertThat(publishedAt(own)).isNotNull();
    }

    @Test
    void alreadyPublishedRowIsNotSentAgain() {
        Long id = insertUnpublished("PREORDER_ORDER_SETTLED", true);
        String eventId = eventIdOf(id);
        Object before = publishedAt(id);

        relay.relay();
        publisher.publishById(id);

        assertThat(sentOf(eventId)).isEmpty();
        assertThat(publishedAt(id)).isEqualTo(before);
    }

    /** 커밋 직후 발행과 릴레이가 같은 행을 둘 다 보냈을 때, 늦은 쪽이 발행 시각을 덮어쓰지 않는다. */
    @Test
    void markPublishedKeepsFirstPublishedAt() {
        Long id = insertUnpublished("PREORDER_ORDER_SETTLED", true);
        Object before = publishedAt(id);

        assertThat(outboxEvents.markPublished(id, Instant.now().plusSeconds(60))).isZero();
        assertThat(publishedAt(id)).isEqualTo(before);
    }

    /**
     * 한 릴레이가 행을 잠근 채 보내는 동안 다른 릴레이는 기다리지 않고 그 행을 건너뛴다(SKIP LOCKED).
     * 잠금만 걸고 건너뛰지 않으면 두 번째가 첫 번째 커밋까지 막힌다. 릴레이가 트랜잭션 밖에서 돌면 잠금이 곧바로 풀려
     * 두 번째가 같은 행을 다시 보내려다 문 앞에서 막힌다 — 어느 쪽이든 제한 시간에 걸린다.
     */
    @Test
    void secondRelaySkipsRowsLockedByFirst() throws Exception {
        List<String> eventIds = IntStream.range(0, 5)
                .mapToObj(i -> insertUnpublished("PREORDER_ORDER_SETTLED", false))
                .map(this::eventIdOf)
                .toList();
        CountDownLatch gate = new CountDownLatch(1);
        sendGate = gate;
        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(relay::relay);
        try {
            assertThat(sendBlocked.await(5, TimeUnit.SECONDS)).as("첫 릴레이가 행을 잠그고 전송에 들어감").isTrue();

            int second = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> relay.relay());

            assertThat(second).isZero();
        } finally {
            sendGate = null;
            gate.countDown();
        }
        assertThat(first.get(10, TimeUnit.SECONDS)).isPositive();
        assertThat(eventIds).allSatisfy(eventId -> assertThat(sentOf(eventId)).hasSize(1));
    }

    @Test
    void concurrentRelaysSendEachRowOnce() throws Exception {
        List<String> eventIds = IntStream.range(0, 30)
                .mapToObj(i -> insertUnpublished("PREORDER_ORDER_SETTLED", false))
                .map(this::eventIdOf)
                .toList();

        List<Outcome<Integer>> outcomes = Concurrently.run(4, i -> () -> relay.relay());

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(eventIds).allSatisfy(eventId -> assertThat(sentOf(eventId)).hasSize(1));
    }

    private PreorderOrderSettled settled() {
        return PreorderOrderSettled.rejected(preorderInternalId, "token-" + preorderInternalId, RejectReason.SHIPPED,
                7L);
    }

    private Long appendCommitted() {
        return transactionTemplate.execute(status -> writer.append(settled()));
    }

    /**
     * 커밋 직후 발행을 거치지 않은 행. 만든 지 1분이 지난 것으로 둔다.
     * 다른 종류 행의 payload 는 쓰이지 않으므로 빈 객체다.
     */
    private Long insertUnpublished(String eventType, boolean published) {
        String eventId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO outbox_events (event_id, aggregate_type, aggregate_id, event_type, payload, created_at,
                                           published_at)
                VALUES (?, 'PREORDER', ?, ?, '{}', UTC_TIMESTAMP(6) - INTERVAL 2 MINUTE,
                        IF(?, UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE, NULL))
                """, eventId, preorderInternalId, eventType, published);
        return jdbcTemplate.queryForObject("SELECT id FROM outbox_events WHERE event_id = ?", Long.class, eventId);
    }

    private void age(Long id) {
        jdbcTemplate.update("UPDATE outbox_events SET created_at = created_at - INTERVAL 2 MINUTE WHERE id = ?", id);
    }

    private Object publishedAt(Long id) {
        return row(id).get("published_at");
    }

    private int attempts(Long id) {
        return ((Number) row(id).get("publish_attempts")).intValue();
    }

    private String eventIdOf(Long id) {
        return (String) row(id).get("event_id");
    }

    private Instant createdAt(Long id) {
        return Instant.parse((String) row(id).get("created_at_utc"));
    }

    private Map<String, Object> row(Long id) {
        return jdbcTemplate.queryForMap("""
                SELECT event_id, publish_attempts, published_at,
                       DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS created_at_utc
                  FROM outbox_events WHERE id = ?
                """, id);
    }

    private List<OutboundMessage> sentOf(String eventId) {
        return sent.stream().filter(message -> message.eventId().equals(eventId)).toList();
    }

    private long onAppendedCalls(Long id) {
        return mockingDetails(afterCommit).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("onAppended"))
                .filter(invocation -> new OutboxAppended(id).equals(invocation.getArgument(0)))
                .count();
    }

    private long aggregateIdOf(OutboundMessage message) {
        return jsonMapper.readTree(message.body()).get("aggregateId").asLong();
    }
}
