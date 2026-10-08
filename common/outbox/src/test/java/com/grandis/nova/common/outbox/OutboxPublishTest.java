package com.grandis.nova.common.outbox;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.outbox.support.OutboxIntegrationTest;
import com.grandis.nova.common.outbox.support.TestOutbox.ItemSettled;
import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;

/**
 * 커밋 직후 발행 · 실패 시 리스 릴레이 재발행. 전송 구현은 받은 메시지를 모으는 대역이다.
 * 커밋 · 롤백을 직접 보려고 테스트 트랜잭션 대신 TransactionTemplate 을 쓴다.
 * DB 를 공유하므로 단정은 이 시험이 만든 행으로만 한다(시험마다 새 aggregate_id).
 */
@OutboxIntegrationTest
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
    OutboxStore store;

    @Autowired
    OutboxRelay relay;

    @Autowired
    OutboxProperties properties;

    @Autowired
    OutboxDefinition definition;

    @Autowired
    Clock clock;

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

    UUID itemId;

    @BeforeEach
    void setUp() {
        itemId = UUID.randomUUID();
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
    void 커밋되면_곧바로_가상_스레드에서_종류의_목적지로_한_번_보내고_발행_완료를_남긴다() {
        Long id = appendCommitted();
        String eventId = eventIdOf(id);

        await().atMost(ASYNC_TIMEOUT).until(() -> publishedAt(id) != null);

        assertThat(sentOf(eventId)).hasSize(1);
        OutboundMessage message = sentOf(eventId).getFirst();
        assertThat(senders.get(eventId).isVirtual()).isTrue();
        assertThat(message.destination()).isEqualTo("test-events");
        assertThat(message.eventType()).isEqualTo("ITEM_SETTLED");
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
    void 커밋_전에는_리스너가_불리지_않는다() {
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

    /** 받는 쪽은 봉투를 EventEnvelope 로 풀고 payload 를 JsonNode 로 받는다. payload 가 문자열로 두 번 싸이면 풀리지 않는다. */
    @Test
    void 봉투에_행과_payload_객체를_싣는다() {
        Long id = appendCommitted();
        String eventId = eventIdOf(id);
        await().atMost(ASYNC_TIMEOUT).until(() -> publishedAt(id) != null);

        JsonNode body = jsonMapper.readTree(sentOf(eventId).getFirst().body());

        assertThat(body.propertyNames()).containsExactly(
                "eventId", "eventType", "aggregateType", "aggregateId", "occurredAt", "payload");
        assertThat(body.get("eventId").asString()).isEqualTo(eventId);
        assertThat(body.get("eventType").asString()).isEqualTo("ITEM_SETTLED");
        assertThat(body.get("aggregateType").asString()).isEqualTo("ITEM");
        assertThat(body.get("aggregateId").asString()).isEqualTo(itemId.toString());
        assertThat(Instant.parse(body.get("occurredAt").asString())).isEqualTo(createdAt(id));
        JsonNode payload = body.get("payload");
        assertThat(payload.isObject()).as("payload 는 문자열이 아닌 JSON 객체").isTrue();
        assertThat(payload.get("result").asString()).isEqualTo("OK");
        assertThat(payload.get("sequence").asLong()).isEqualTo(3L);
    }

    @Test
    void 롤백되면_발행하지_않는다() {
        Long id = transactionTemplate.execute(status -> {
            Long appended = writer.append(settled());
            status.setRollbackOnly();
            return appended;
        });

        // 커밋 후 콜백 자체가 불리지 않으므로 잠시 기다려도 나가는 것이 없어야 한다
        await().during(Duration.ofMillis(300)).atMost(ASYNC_TIMEOUT)
                .until(() -> sent.stream().noneMatch(message -> itemId.equals(aggregateIdOf(message))));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM it_outbox_events WHERE id = ?", Integer.class, id))
                .isZero();
    }

    @Test
    void 전송이_실패하면_미발행으로_남고_릴레이가_1분_뒤_다시_보낸다() {
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

    @Test
    void 이미_발행된_행은_다시_보내지_않는다() {
        Long id = insertUnpublished("ITEM_SETTLED", true);
        Object before = publishedAt(id);

        relay.relay();
        publisher.publishById(id);

        assertThat(sentOf(eventIdOf(id))).isEmpty();
        assertThat(publishedAt(id)).isEqualTo(before);
    }

    /** 커밋 직후 발행과 릴레이가 같은 행을 둘 다 보냈을 때, 늦은 쪽이 발행 시각을 덮어쓰지 않는다. */
    @Test
    void 발행_완료_표시는_처음_시각을_지킨다() {
        Long id = insertUnpublished("ITEM_SETTLED", true);
        Object before = publishedAt(id);

        assertThat(store.markPublished(id, Instant.now().plusSeconds(60))).isZero();
        assertThat(publishedAt(id)).isEqualTo(before);
    }

    /** 표가 서비스 것이라 종류로 거르지 않는다. 행마다 자기 종류의 목적지로 간다. */
    @Test
    void 릴레이는_종류마다_그_목적지로_보낸다() {
        Long settled = insertUnpublished("ITEM_SETTLED", false);
        Long notified = insertUnpublished("ITEM_NOTIFIED", false);

        relay.relay();

        assertThat(sentOf(eventIdOf(settled))).singleElement()
                .extracting(OutboundMessage::destination).isEqualTo("test-events");
        assertThat(sentOf(eventIdOf(notified))).singleElement()
                .extracting(OutboundMessage::destination).isEqualTo("notification");
    }

    /** 코드에서 지운 종류의 행은 보낼 곳이 없다. 실패로 세어 드러내고, 다른 행은 막지 않는다. */
    @Test
    void 목적지를_모르는_종류의_행은_실패로_센다() {
        Long unknown = insertUnpublished("ITEM_REMOVED_" + itemId, false);
        Long known = insertUnpublished("ITEM_SETTLED", false);

        relay.relay();

        assertThat(attempts(unknown)).isEqualTo(1);
        assertThat(publishedAt(unknown)).isNull();
        assertThat(publishedAt(known)).isNotNull();
        // 다른 시험의 릴레이가 이 행을 계속 집지 않게 치운다
        jdbcTemplate.update("UPDATE it_outbox_events SET published_at = UTC_TIMESTAMP(6) WHERE id = ?", unknown);
    }

    @Test
    void 여러_인스턴스가_동시에_릴레이해도_한_행은_한_번만_보낸다() throws Exception {
        List<String> eventIds = IntStream.range(0, 30)
                .mapToObj(i -> insertUnpublished("ITEM_SETTLED", false))
                .map(this::eventIdOf)
                .toList();

        List<Outcome<Integer>> outcomes = Concurrently.run(4, i -> () -> relay.relay());

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(eventIds).allSatisfy(eventId -> assertThat(sentOf(eventId)).hasSize(1));
    }

    @Test
    void 보내는_동안_행_잠금을_쥐지_않고_리스만_건다() throws Exception {
        Long id = insertUnpublished("ITEM_SETTLED", false);
        blockedEventId = eventIdOf(id);

        CompletableFuture<Integer> relaying = CompletableFuture.supplyAsync(relay::relay);
        assertThat(sending.await(ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        Long locked = transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
                "SELECT id FROM it_outbox_events WHERE id = ? FOR UPDATE NOWAIT", Long.class, id));
        assertThat(locked).as("다른 트랜잭션이 기다리지 않고 잠근다").isEqualTo(id);
        assertThat(leaseUntil(id)).isNotNull();

        released.countDown();
        relaying.get(ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(publishedAt(id)).isNotNull();
    }

    @Test
    void 리스_중인_행은_건너뛰고_리스가_끝나면_다시_가져간다() {
        Long id = insertUnpublished("ITEM_SETTLED", false);
        jdbcTemplate.update(
                "UPDATE it_outbox_events SET lease_until = UTC_TIMESTAMP(6) + INTERVAL 5 MINUTE WHERE id = ?", id);

        relay.relay();
        assertThat(sentOf(eventIdOf(id))).as("다른 인스턴스가 보내는 중").isEmpty();

        jdbcTemplate.update(
                "UPDATE it_outbox_events SET lease_until = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE WHERE id = ?", id);
        relay.relay();

        assertThat(publishedAt(id)).as("보내던 인스턴스가 죽어 리스가 끝났다").isNotNull();
        assertThat(sentOf(eventIdOf(id))).hasSize(1);
    }

    @Test
    void 릴레이_전송이_실패하면_시도_횟수를_올리고_리스를_풀어_다음_주기에_다시_보낸다() {
        Long id = insertUnpublished("ITEM_SETTLED", false);
        transportDown = true;

        relay.relay();

        assertThat(attempts(id)).isEqualTo(1);
        assertThat(leaseUntil(id)).isNull();
        assertThat(publishedAt(id)).isNull();

        transportDown = false;
        relay.relay();
        assertThat(publishedAt(id)).isNotNull();
    }

    /** 늘 실패하는 행(없는 큐 등)이 묶음 앞자리를 차지해 다른 행을 막지 않는다. */
    @Test
    void 늘_실패하는_행이_앞에_쌓여도_실패가_적은_행을_먼저_가져간다() {
        Long failing = insertUnpublished("ITEM_SETTLED", false);
        jdbcTemplate.update("UPDATE it_outbox_events SET publish_attempts = 1000000 WHERE id = ?", failing);
        Long fresh = insertUnpublished("ITEM_SETTLED", false);

        // 다른 시험이 남긴 미발행 행이 있어도 이 시험의 두 행 사이 순서만 본다
        List<Long> claimed = transactionTemplate.execute(status -> store
                .lockClaimable(Instant.now(), Instant.now(), Integer.MAX_VALUE).stream()
                .map(OutboxRow::id).filter(id -> id.equals(failing) || id.equals(fresh)).toList());

        assertThat(claimed).containsExactly(fresh, failing);
    }

    @Test
    void 보내기_전에_리스가_끝나_다른_인스턴스가_가져간_행은_보내지_않는다() {
        Long id = insertUnpublished("ITEM_SETTLED", false);
        // 가져간 뒤(첫 읽기 뒤) 보내기 직전마다, 다른 인스턴스가 이 행에 리스를 새로 건 것으로 만든다.
        // 다른 시험이 남긴 행이 같은 묶음에 먼저 와도 이 행의 차례에는 리스가 이미 남의 것이다
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
                if (++reads >= 2) {
                    jdbcTemplate.update("UPDATE it_outbox_events SET lease_until = UTC_TIMESTAMP(6) + INTERVAL 5 MINUTE"
                            + " WHERE id = ?", id);
                }
                return Instant.now().truncatedTo(ChronoUnit.MICROS);
            }
        };

        new OutboxRelay(store, publisher, properties, transactionTemplate, takenOver).relay();

        assertThat(sentOf(eventIdOf(id))).isEmpty();
        assertThat(publishedAt(id)).isNull();
    }

    /** 종료할 때 진행 중인 릴레이는 보내던 행만 마치고, 남은 행은 리스를 풀어 다른 인스턴스가 곧바로 가져가게 한다. */
    @Test
    void 멈추라고_하면_보내던_행만_마치고_남은_행의_리스를_푼다() throws Exception {
        Long inFlight = insertUnpublished("ITEM_SETTLED", false);
        List<Long> rest = List.of(insertUnpublished("ITEM_SETTLED", false), insertUnpublished("ITEM_SETTLED", false));
        blockedEventId = eventIdOf(inFlight);
        CompletableFuture<Integer> relaying = CompletableFuture.supplyAsync(relay::relay);
        try {
            assertThat(sending.await(ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();

            relay.requestStop();
            released.countDown();
            relaying.get(ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } finally {
            relay.resume();
        }

        assertThat(publishedAt(inFlight)).isNotNull();
        assertThat(rest).allSatisfy(id -> {
            assertThat(sentOf(eventIdOf(id))).isEmpty();
            assertThat(leaseUntil(id)).as("리스를 풀어 다음 릴레이가 곧바로 가져간다").isNull();
            assertThat(attempts(id)).as("보내지 않은 행은 실패로 세지 않는다").isZero();
        });
        assertThat(relay.relay()).as("다시 돌리면 남은 행을 보낸다").isGreaterThanOrEqualTo(2);
        assertThat(rest).allSatisfy(id -> assertThat(publishedAt(id)).isNotNull());
    }

    /** 한 행에서 DB 오류가 나 묶음이 끝나도, 남은 행은 리스가 끝날 때까지 묶이지 않고 다음 릴레이가 곧바로 가져간다. */
    @Test
    void 한_행의_DB_오류로_멈추면_남은_행의_리스를_푼다() {
        Long failing = insertUnpublished("ITEM_SETTLED", false);
        List<Long> rest = List.of(insertUnpublished("ITEM_SETTLED", false), insertUnpublished("ITEM_SETTLED", false));
        OutboxStore failingStore = spy(store);
        willThrow(new DataAccessResourceFailureException("db down")).given(failingStore).markPublished(eq(failing), any());
        OutboxPublisher failingPublisher = new OutboxPublisher(failingStore, definition, transport, jsonMapper, clock,
                OutboxMetrics.NONE);

        assertThatThrownBy(() -> new OutboxRelay(failingStore, failingPublisher, properties, transactionTemplate, clock)
                .relay()).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(leaseUntil(failing)).as("보낸 행은 연장한 리스가 남아 그 사이 다시 보내지 않는다").isNotNull();
        assertThat(rest).allSatisfy(id -> {
            assertThat(sentOf(eventIdOf(id))).isEmpty();
            assertThat(leaseUntil(id)).as("남은 행은 바로 다시 가져갈 수 있다").isNull();
        });
        jdbcTemplate.update("UPDATE it_outbox_events SET published_at = UTC_TIMESTAMP(6) WHERE id = ?", failing);
    }

    /** 종료 시간을 넘겨 남은 실행은 그 사이 다시 시작해도(resume) 되살아나지 않는다 — 새 실행과 나란히 돌지 않게. */
    @Test
    void 멈춘_실행은_다시_시작해도_남은_행을_보내지_않는다() throws Exception {
        Long inFlight = insertUnpublished("ITEM_SETTLED", false);
        Long next = insertUnpublished("ITEM_SETTLED", false);
        blockedEventId = eventIdOf(inFlight);
        CompletableFuture<Integer> relaying = CompletableFuture.supplyAsync(relay::relay);
        try {
            assertThat(sending.await(ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();

            relay.requestStop();
            relay.resume();
            released.countDown();
            relaying.get(ASYNC_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } finally {
            relay.resume();
        }

        assertThat(sentOf(eventIdOf(next))).isEmpty();
        assertThat(leaseUntil(next)).isNull();
    }

    /** 리스 값이 소유 표식이라 적은 값과 들고 있는 값이 같아야 한다. 서비스 시계가 나노초(리눅스 기본 시계)여도 같다. */
    @Test
    void 나노초_시각으로_건_리스도_내_리스로_알아본다() {
        Long id = insertUnpublished("ITEM_SETTLED", false);
        Instant lease = Instant.parse("2026-10-01T00:00:00.123456789Z");
        transactionTemplate.executeWithoutResult(status -> store.lease(List.of(id), lease));

        assertThat(store.renewLease(id, lease, lease.plusSeconds(1))).isOne();
        store.recordFailure(id, lease.plusSeconds(1));

        assertThat(leaseUntil(id)).as("내 리스라 실패하면 푼다").isNull();
        jdbcTemplate.update("UPDATE it_outbox_events SET published_at = UTC_TIMESTAMP(6) WHERE id = ?", id);
    }

    @Test
    void 남의_리스는_연장하지도_늦게_실패한_쪽이_풀지도_않는다() {
        Long id = insertUnpublished("ITEM_SETTLED", false);
        Instant mine = Instant.parse("2026-01-01T00:00:00Z");
        jdbcTemplate.update(
                "UPDATE it_outbox_events SET lease_until = UTC_TIMESTAMP(6) + INTERVAL 5 MINUTE WHERE id = ?", id);
        Object theirs = leaseUntil(id);

        assertThat(store.renewLease(id, mine, Instant.now())).isZero();
        store.recordFailure(id, mine);

        assertThat(leaseUntil(id)).isEqualTo(theirs);
        assertThat(attempts(id)).as("실패 횟수는 센다").isEqualTo(1);
    }

    private ItemSettled settled() {
        return new ItemSettled(itemId, "OK", 3L);
    }

    private Long appendCommitted() {
        return transactionTemplate.execute(status -> writer.append(settled()));
    }

    /** 커밋 직후 발행을 거치지 않은 행. 만든 지 1분이 지난 것으로 둔다. */
    private Long insertUnpublished(String eventType, boolean published) {
        String eventId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO it_outbox_events (event_id, aggregate_type, aggregate_id, event_type, payload, created_at,
                                              published_at)
                VALUES (?, 'ITEM', ?, ?, '{}', UTC_TIMESTAMP(6) - INTERVAL 2 MINUTE,
                        IF(?, UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE, NULL))
                """, eventId, UuidBinary.toBytes(itemId), eventType, published);
        return jdbcTemplate.queryForObject("SELECT id FROM it_outbox_events WHERE event_id = ?", Long.class, eventId);
    }

    private void age(Long id) {
        jdbcTemplate.update("UPDATE it_outbox_events SET created_at = created_at - INTERVAL 2 MINUTE WHERE id = ?", id);
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

    private Instant createdAt(Long id) {
        return Instant.parse((String) row(id).get("created_at_utc"));
    }

    private Map<String, Object> row(Long id) {
        return jdbcTemplate.queryForMap("""
                SELECT event_id, publish_attempts, lease_until, published_at,
                       DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') AS created_at_utc
                  FROM it_outbox_events WHERE id = ?
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

    private UUID aggregateIdOf(OutboundMessage message) {
        return UUID.fromString(jsonMapper.readTree(message.body()).get("aggregateId").asString());
    }
}
