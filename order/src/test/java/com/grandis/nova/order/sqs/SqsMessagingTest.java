package com.grandis.nova.order.sqs;

import com.grandis.nova.common.outbox.MessageTransport;
import com.grandis.nova.common.sqs.RetryingQueueConsumer;
import com.grandis.nova.common.sqs.SqsQueueUrls;
import com.grandis.nova.common.sqs.testing.TestQueues;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.enums.OrderTrigger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.PlacedOrders;
import com.grandis.nova.order.support.SqsIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * order 의 SQS 배선을 끝에서 끝으로 본다 — 실제 SQS 프로토콜(Floci)로 order-events 를 받아 주문을 정리하고, 정리 결과가
 * preorder-events 로 나가는지, 정할 수 없는 요청이 DLQ 로 가는지. 받기 · 지우기 · 다시 보이기 자체는 common:sqs 시험이 본다.
 */
@SqsIntegrationTest
class SqsMessagingTest {

    static final Duration TIMEOUT = Duration.ofSeconds(15);
    static final String QUEUE = "order-events";

    @Autowired
    TestQueues queues;

    @Autowired
    ApplicationContext context;

    @Autowired
    OrderLedger ledger;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    SqsClient sqs;

    @Autowired
    SqsQueueUrls queueUrls;

    OrderFixtures fixtures;
    PlacedOrders placedOrders;
    Long customerId;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        placedOrders = new PlacedOrders(ledger, transactionTemplate, fixtures);
        customerId = fixtures.customer();
    }

    /** 설정(nova.sqs.consumer.enabled · transport=sqs)이 공통 소비기 · SQS 전송으로 이어진다. */
    @Test
    void 소비기와_전송이_공통_SQS_모듈로_엮인다() {
        assertThat(context.getBean("orderEventConsumer")).isInstanceOf(RetryingQueueConsumer.class);
        assertThat(context.getBean("orderEventConsumer", RetryingQueueConsumer.class).isRunning()).isTrue();
        assertThat(context.getBean(MessageTransport.class).getClass().getSimpleName()).isEqualTo("SqsMessageTransport");
    }

    /** 예약 취소 요청 수신 → 주문 정리 → 커밋 직후 발행으로 preorder 가 받을 정리 결과가 preorder-events 에 도착한다. */
    @Test
    void 취소_요청을_정리하면_정리_결과가_preorder_events_로_나간다() {
        Order order = placedOrders.place(customerId);
        String preorderUuid = preorderUuidOf(order);

        queues.send(QUEUE, cancelRequested(order, "USER"));

        Message settled = queues.receive("preorder-events", m -> m.body().contains(preorderUuid), TIMEOUT)
                .orElseThrow();
        JsonNode body = jsonMapper.readTree(settled.body());
        assertThat(body.get("eventType").asString()).isEqualTo("PREORDER_ORDER_SETTLED");
        assertThat(body.get("aggregateType").asString()).isEqualTo("PREORDER");
        assertThat(body.get("aggregateId").asLong()).isEqualTo(order.preorderId());
        assertThat(body.get("payload").get("preorderId").asString()).isEqualTo(preorderUuid);
        assertThat(body.get("payload").get("result").asString()).isEqualTo("CANCELED");
        assertThat(body.get("payload").get("cancelSequence").asLong()).isEqualTo(1L);
        // 정리는 발행보다 먼저 커밋되므로 메시지가 왔으면 상태는 이미 바뀌어 있다
        assertThat(statusOf(order)).isEqualTo("CANCELED");
        // 발행 완료 표시는 전송이 돌아온 뒤 발행 스레드가 적는다 — 큐에서 먼저 받을 수 있으므로 기다린다
        String eventId = body.get("eventId").asString();
        await().atMost(TIMEOUT).until(() -> jdbcTemplate.queryForObject(
                "SELECT published_at IS NOT NULL FROM order_outbox_events WHERE event_id = ?", Boolean.class, eventId));
    }

    /**
     * 결제된 주문의 사용자 취소: 취소 중 + 환불 요청이 payment-events 로 나가고, 취소 메시지는 늦춰 다시 받는다(재수신 한도에 걸리지
     * 않는다). 환불 결과가 오면 취소되고, 다시 받은 취소 메시지가 정리 결과(CANCELED)를 preorder-events 로 보낸다.
     */
    @Test
    void 결제된_주문의_취소는_환불이_끝날_때까지_늦춰_다시_받고_끝나면_CANCELED_를_보낸다() {
        Order order = placedOrders.place(customerId);
        fixtures.forceStatus(order.id(), "AWAITING_CONFIRMATION");
        String preorderUuid = preorderUuidOf(order);

        queues.send(QUEUE, cancelRequested(order, "USER"));

        Message refundRequest = queues.receive("payment-events",
                m -> m.body().contains("ORDER_REFUND_REQUESTED") && m.body().contains("\"aggregateId\":" + order.id()),
                TIMEOUT).orElseThrow();
        assertThat(jsonMapper.readTree(refundRequest.body()).get("aggregateType").asString()).isEqualTo("ORDER");
        assertThat(statusOf(order)).isEqualTo("CANCELING");
        // 재수신 한도(2회)의 몇 배를 기다려도 DLQ 로 가지 않는다 — 실패로 늦춘 것이 아니라 새로 보냈다
        assertThat(queues.receive(QUEUE + "-dlq", m -> m.body().contains(preorderUuid), Duration.ofSeconds(8)))
                .isEmpty();
        assertThat(settledRows(order)).isZero();

        queues.send(QUEUE, refundSettled(order, "REFUNDED"));

        Message settled = queues.receive("preorder-events", m -> m.body().contains(preorderUuid), TIMEOUT)
                .orElseThrow();
        assertThat(jsonMapper.readTree(settled.body()).get("payload").get("result").asString()).isEqualTo("CANCELED");
        assertThat(statusOf(order)).isEqualTo("CANCELED");
    }

    /**
     * 환불이 확정 실패해도, 오래 보류됐어도 메시지를 버리지 않는다 — 사람이 해소해 취소되면 다시 받은 메시지가 CANCELED 를 보낸다(U1).
     * 경보 기준이 지난 메시지로 보낸다(속성 firstDeferredAt 을 2시간 전으로).
     */
    @Test
    void 환불이_실패해_오래_보류된_취소도_버리지_않고_해소되면_CANCELED_를_보낸다() {
        Order order = placedOrders.place(customerId);
        fixtures.forceStatus(order.id(), "CANCELING");
        transactionTemplate.executeWithoutResult(s -> ledger.noteRefundFailed(order.id()));
        String preorderUuid = preorderUuidOf(order);
        String body = cancelRequested(order, "USER");

        sqs.sendMessage(request -> request.queueUrl(queueUrls.of(QUEUE)).messageBody(body).messageAttributes(Map.of(
                "deferCount", MessageAttributeValue.builder().dataType("Number").stringValue("90").build(),
                "firstDeferredAt", MessageAttributeValue.builder().dataType("String")
                        .stringValue(Instant.now().minus(Duration.ofHours(2)).toString()).build())));

        assertThat(queues.receive(QUEUE + "-dlq", m -> m.body().equals(body), Duration.ofSeconds(8))).isEmpty();
        assertThat(settledRows(order)).isZero();

        // 사람이 환불을 해소해 취소로 끝낸다(관리자 경로는 후속 — 여기서는 원장으로 흉내 낸다)
        transactionTemplate.executeWithoutResult(s -> ledger.fire(order.id(), OrderTrigger.REFUND_COMPLETED,
                EnumSet.of(OrderStatus.CANCELING), EventCause.system("REFUND_COMPLETED")));

        Message settled = queues.receive("preorder-events", m -> m.body().contains(preorderUuid), TIMEOUT)
                .orElseThrow();
        assertThat(jsonMapper.readTree(settled.body()).get("payload").get("result").asString()).isEqualTo("CANCELED");
    }

    /** payment 가 보내는 모양 그대로(aggregateId = 주문 id, 결제 키 없음). */
    private String refundSettled(Order order, String result) {
        return """
                {"eventId":"%s","eventType":"ORDER_REFUND_SETTLED","aggregateType":"ORDER",
                 "aggregateId":%d,"occurredAt":"2026-10-04T01:20:31Z",
                 "payload":{"result":"%s","amount":%s,"refundedAt":"2026-10-04T01:20:30Z"}}
                """.formatted(OrderFixtures.unique(), order.id(), result, order.totalAmount().amount().toPlainString());
    }

    /** preorder 가 보내는 모양 그대로(aggregateId = 예약 내부 id, payload 에는 공개 UUID). */
    private String cancelRequested(Order order, String reason) {
        String preorderUuid = preorderUuidOf(order);
        return """
                {"eventId":"%s","eventType":"PREORDER_CANCEL_REQUESTED","aggregateType":"PREORDER",
                 "aggregateId":%d,"occurredAt":"2026-09-03T01:00:03.470Z",
                 "payload":{"preorderId":"%s","customerId":%d,"reason":"%s","cancelSequence":1}}
                """.formatted(OrderFixtures.unique(), order.preorderId(), preorderUuid, order.customerId(), reason);
    }

    private String preorderUuidOf(Order order) {
        return jdbcTemplate.queryForObject("SELECT preorder_token FROM preorders WHERE id = ?",
                String.class, order.preorderId());
    }

    private String statusOf(Order order) {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order.id());
    }

    private int settledRows(Order order) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM order_outbox_events
                 WHERE aggregate_type = 'PREORDER' AND aggregate_id = ? AND event_type = 'PREORDER_ORDER_SETTLED'
                """, Integer.class, order.preorderId());
    }
}
