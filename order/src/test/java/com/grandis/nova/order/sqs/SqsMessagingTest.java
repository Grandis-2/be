package com.grandis.nova.order.sqs;

import com.grandis.nova.order.event.OrderEventDispatcher;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.PlacedOrders;
import com.grandis.nova.order.support.SqsIntegrationTest;
import com.grandis.nova.order.support.TestQueues;
import com.grandis.nova.order.support.containers.FlociTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mockingDetails;

/**
 * 실제 SQS 프로토콜(Floci)로 order-events 를 받아 주문을 정리하고, 정리 결과가 preorder-events 로 나가는지,
 * 처리하지 못한 메시지가 DLQ 로 가는지 확인한다.
 */
@SqsIntegrationTest
class SqsMessagingTest {

    static final Duration TIMEOUT = Duration.ofSeconds(15);
    static final String QUEUE = "order-events";

    @Autowired
    TestQueues queues;

    /** 호출 기록만 읽어 메시지가 몇 번 처리됐는지 센다(스텁하지 않는다). */
    @MockitoSpyBean
    OrderEventDispatcher dispatcher;

    @Autowired
    OrderLedger ledger;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    OrderFixtures fixtures;
    PlacedOrders placedOrders;
    Long customerId;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        placedOrders = new PlacedOrders(ledger, transactionTemplate, fixtures);
        customerId = fixtures.customer();
    }

    @Test
    void 받은_취소_요청을_처리하면_주문을_취소하고_그_메시지를_지운다() {
        Order order = placedOrders.place(customerId);
        String body = cancelRequested(order, "USER");

        queues.send(QUEUE, body);

        await().atMost(TIMEOUT).until(() -> "CANCELED".equals(statusOf(order)));
        assertThat(settledRows(order)).isEqualTo(1);
        // 지우지 못했다면 가시성 시간(2s) 뒤 다시 보여 한 번 더 처리된다 — 그 몇 배를 기다려도 한 번이어야 한다
        await().alias("처리한 메시지는 지워져 다시 처리되지 않는다")
                .during(Duration.ofSeconds(8)).atMost(TIMEOUT)
                .until(() -> dispatchCount(body) == 1);
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
                "SELECT published_at IS NOT NULL FROM outbox_events WHERE event_id = ?", Boolean.class, eventId));
    }

    /** 결제된 주문의 사용자 취소는 환불(결제 작업)이 필요하다. 결과를 적지 않고, 지우지 않아 DLQ 로 간다. */
    @Test
    void 결과를_정할_수_없는_요청은_지우지_않아_DLQ_로_간다() {
        Order order = placedOrders.place(customerId);
        fixtures.forceStatus(order.id(), "AWAITING_CONFIRMATION");
        String body = cancelRequested(order, "USER");

        queues.send(QUEUE, body);

        assertThat(queues.receive(QUEUE + "-dlq", m -> m.body().equals(body), Duration.ofSeconds(30)))
                .as("%d 번 받고도 처리하지 못하면 DLQ", FlociTestContainer.MAX_RECEIVE_COUNT)
                .isPresent();
        assertThat(statusOf(order)).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(settledRows(order)).isZero();
    }

    @Test
    void 처리하지_못하는_메시지는_다시_받다가_DLQ_로_간다() {
        String poison = "not-json-" + OrderFixtures.unique();

        queues.send(QUEUE, poison);

        assertThat(queues.receive(QUEUE + "-dlq", m -> m.body().equals(poison), Duration.ofSeconds(30)))
                .as("%d 번 받고도 처리하지 못하면 DLQ", FlociTestContainer.MAX_RECEIVE_COUNT)
                .isPresent();
    }

    /** 빈 큐의 롱 폴링은 대기 시간만큼 걸린다. 클라이언트 기본 제한 시간에 걸려 받기가 실패하면 안 된다. */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 롱_폴링이_호출_제한_시간에_걸리지_않는다(CapturedOutput output) {
        await().during(Duration.ofSeconds(8)).atMost(Duration.ofSeconds(10))
                .until(() -> !output.getOut().contains("이벤트 큐를 받지 못했다"));
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

    private long dispatchCount(String body) {
        return mockingDetails(dispatcher).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("dispatch"))
                .filter(invocation -> body.equals(invocation.getArgument(0)))
                .count();
    }

    private String statusOf(Order order) {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order.id());
    }

    private int settledRows(Order order) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM outbox_events
                 WHERE aggregate_type = 'PREORDER' AND aggregate_id = ? AND event_type = 'PREORDER_ORDER_SETTLED'
                """, Integer.class, order.preorderId());
    }
}
