package com.grandis.nova.order.outbox;

import com.grandis.nova.order.outbox.PreorderOrderSettled.RejectReason;
import com.grandis.nova.order.support.SqsIntegrationTest;
import com.grandis.nova.order.support.TestQueues;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;

/**
 * 실제 SQS 프로토콜(Floci)로 커밋 직후 발행과 릴레이 재발행이 preorder-events 큐에 닿는지 본다.
 *
 * 소비기는 끈다. 이 클래스는 전송을 감싸 컨텍스트가 SqsMessagingTest 와 따로 뜨는데(DB 컨테이너도 따로), 캐시된 두
 * 컨텍스트의 소비기가 같은 order-events 큐를 나눠 받으면 한쪽 테스트의 메시지를 다른 DB 에서 처리해 버린다.
 * 이 클래스는 preorder-events 에 보내기만 하므로 소비기가 필요 없다.
 *
 * common:outbox 이전 시: preorder sqs.SqsMessagingTest 의 발행 시나리오와 같다. 공통으로 옮길 때 한 벌로 합친다.
 */
@SqsIntegrationTest
@TestPropertySource(properties = "nova.sqs.consumer.enabled=false")
class OutboxSqsPublishTest {

    static final Duration TIMEOUT = Duration.ofSeconds(15);
    static final String QUEUE = "preorder-events";

    @Autowired
    OutboxWriter writer;

    @Autowired
    OutboxRelay relay;

    @Autowired
    TestQueues queues;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    ApplicationContext context;

    /** 실제 SQS 전송을 감싼다. transportDown 일 때만 보내기 전에 실패시킨다. */
    @MockitoSpyBean
    MessageTransport transport;

    volatile boolean transportDown;

    long preorderInternalId;

    @BeforeEach
    void setUp() throws Throwable {
        preorderInternalId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        willAnswer(invocation -> {
            if (transportDown) {
                throw new IllegalStateException("transport down");
            }
            return invocation.callRealMethod();
        }).given(transport).send(any());
    }

    /** transport=sqs 가 빈 조건 판정 전에 들어갔는지, 소비기가 정말 꺼졌는지(클래스 설명의 전제) 본다. */
    @Test
    void usesSqsTransportWithoutConsumer() {
        assertThat(context.getBeansOfType(LoggingMessageTransport.class)).isEmpty();
        assertThat(context.getBeansOfType(MessageTransport.class)).hasSize(1);
        assertThat(context.containsBean("orderEventConsumer")).isFalse();
    }

    @Test
    void commitSendsEnvelopeToPreorderEventsQueue() {
        Long id = appendCommitted();
        String eventId = eventIdOf(id);

        Message message = receive(eventId);

        assertThat(message.messageAttributes().get("eventType").stringValue()).isEqualTo("PREORDER_ORDER_SETTLED");
        assertThat(message.messageAttributes().get("eventId").stringValue()).isEqualTo(eventId);
        JsonNode body = jsonMapper.readTree(message.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder(
                "eventId", "eventType", "aggregateType", "aggregateId", "occurredAt", "payload");
        assertThat(body.get("eventId").asString()).isEqualTo(eventId);
        assertThat(body.get("eventType").asString()).isEqualTo("PREORDER_ORDER_SETTLED");
        assertThat(body.get("aggregateType").asString()).isEqualTo("PREORDER");
        assertThat(body.get("aggregateId").asLong()).isEqualTo(preorderInternalId);
        assertThat(body.get("occurredAt").isString()).isTrue();
        JsonNode payload = body.get("payload");
        assertThat(payload.isObject()).as("payload 는 문자열이 아닌 JSON 객체").isTrue();
        assertThat(payload.get("preorderId").asString()).isEqualTo("token-" + preorderInternalId);
        assertThat(payload.get("result").asString()).isEqualTo("REJECTED");
        assertThat(payload.get("reason").asString()).isEqualTo("SHIPPED");
        assertThat(payload.get("cancelSequence").asLong()).isEqualTo(7L);
        await().atMost(TIMEOUT).until(() -> row(id).get("published_at") != null);
    }

    @Test
    void failedSendStaysUnpublishedAndRelayDeliversLater() {
        transportDown = true;
        Long id = appendCommitted();
        String eventId = eventIdOf(id);
        await().atMost(TIMEOUT).until(() -> attempts(id) == 1);
        assertThat(row(id).get("published_at")).isNull();

        transportDown = false;
        jdbcTemplate.update("UPDATE outbox_events SET created_at = created_at - INTERVAL 2 MINUTE WHERE id = ?", id);
        relay.relay();

        assertThat(row(id).get("published_at")).isNotNull();
        assertThat(jsonMapper.readTree(receive(eventId).body()).get("eventId").asString()).isEqualTo(eventId);
    }

    private Long appendCommitted() {
        return transactionTemplate.execute(status -> writer.append(PreorderOrderSettled.rejected(
                preorderInternalId, "token-" + preorderInternalId, RejectReason.SHIPPED, 7L)));
    }

    private Message receive(String eventId) {
        return queues.receive(QUEUE, m -> m.body().contains(eventId), TIMEOUT).orElseThrow();
    }

    private String eventIdOf(Long id) {
        return (String) row(id).get("event_id");
    }

    private int attempts(Long id) {
        return ((Number) row(id).get("publish_attempts")).intValue();
    }

    private Map<String, Object> row(Long id) {
        return jdbcTemplate.queryForMap(
                "SELECT event_id, publish_attempts, published_at FROM outbox_events WHERE id = ?", id);
    }
}
