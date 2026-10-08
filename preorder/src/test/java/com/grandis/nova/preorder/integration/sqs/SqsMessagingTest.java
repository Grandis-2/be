package com.grandis.nova.preorder.integration.sqs;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.outbox.MessageTransport;
import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.common.sqs.testing.FlociTestContainer;
import com.grandis.nova.common.sqs.testing.TestQueues;
import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.accept.application.RegisterJobReady;
import com.grandis.nova.preorder.deadletter.DeadLetters;
import com.grandis.nova.preorder.deadletter.IncomingDeadLetter;
import com.grandis.nova.preorder.event.PreorderEventDispatcher;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.support.SqsIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

/** 실제 SQS 프로토콜(Floci)로 발행 · 소비 · DLQ 와 흐름 ①(접수 → 외부 등록 → 결제 가능)을 확인한다. */
@SqsIntegrationTest
class SqsMessagingTest {

    static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    OutboxWriter writer;

    @Autowired
    TestQueues queues;

    /** 실제 처리는 그대로 하고, 받은 횟수(진입)와 끝까지 처리한 횟수(정상 반환)를 센다. */
    @MockitoSpyBean
    PreorderEventDispatcher dispatcher;

    final Map<String, Integer> handled = new ConcurrentHashMap<>();

    /** DLQ 적재는 그대로 하고, 적재한 스레드 이름을 본문별로 남긴다. */
    @MockitoSpyBean
    DeadLetters deadLetters;

    /** 메시지 본문 → 그것을 처리한 소비 스레드 이름. 스레드 이름은 큐 이름-n 이다(운영 로그 · 스레드 덤프가 이 이름을 쓴다). */
    final Map<String, String> threads = new ConcurrentHashMap<>();

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    ApplicationContext context;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        doAnswer(invocation -> {
            threads.put(invocation.getArgument(0), Thread.currentThread().getName());
            invocation.callRealMethod();
            handled.merge(invocation.getArgument(0), 1, Integer::sum);
            return null;
        }).when(dispatcher).dispatch(anyString());
        doAnswer(invocation -> {
            threads.put(invocation.<IncomingDeadLetter>getArgument(0).body(), Thread.currentThread().getName());
            return invocation.callRealMethod();
        }).when(deadLetters).record(any());
    }

    /** SQS 클라이언트는 하나뿐이고, 아웃박스는 common:sqs 의 전송으로 보낸다. */
    @Test
    void 아웃박스_전송은_common_sqs_의_SQS_전송이다() {
        assertThat(context.getBeansOfType(SqsClient.class)).hasSize(1);
        assertThat(context.getBean(MessageTransport.class).getClass().getName())
                .isEqualTo("com.grandis.nova.common.sqs.SqsMessageTransport");
    }

    @Test
    void 커밋되면_목적지_큐로_봉투를_보낸다() {
        UUID syncJobId = UUID.randomUUID();
        Long id = transactionTemplate.execute(status -> writer.append(new RegisterJobReady(syncJobId, "9f1c2d3e")));
        String eventId = jdbcTemplate.queryForObject(
                "SELECT event_id FROM preorder_outbox_events WHERE id = ?", String.class, id);

        Message message = queues.receive("preorder-register", m -> m.body().contains(eventId), TIMEOUT).orElseThrow();

        JsonNode body = jsonMapper.readTree(message.body());
        assertThat(body.get("eventType").asString()).isEqualTo("REGISTER_JOB_READY");
        assertThat(body.get("payload").get("syncJobId").asString()).isEqualTo(syncJobId.toString());
        assertThat(message.messageAttributes().get("eventType").stringValue()).isEqualTo("REGISTER_JOB_READY");
    }

    @Test
    void 받은_이벤트를_처리하면_그_메시지를_큐에서_지운다() {
        AcceptResult accepted = new AcceptFixtures(acceptService, fixtures, catalogClient).accept(fixtures.customer());
        UUID preorderId = accepted.preorder().id();
        String eventId = ShopFixtures.unique();
        String externalNumber = "R-" + ShopFixtures.unique();

        String body = externalJobSucceeded(eventId, fixtures.workerSucceeds(preorderId, "REGISTER"),
                AcceptFixtures.tokenOf(accepted), externalNumber);

        queues.send("preorder-events", body);

        await().atMost(TIMEOUT).until(() -> "REGISTERED".equals(status(preorderId)));
        assertThat(jdbcTemplate.queryForObject("SELECT external_reference FROM preorders WHERE id = ?",
                String.class, (Object) UuidBinary.toBytes(preorderId))).isEqualTo(externalNumber);
        // 지우지 못했다면 가시성 시간(2s) 뒤 다시 보여 한 번 더 처리된다 — 그 몇 배를 기다려도 한 번이어야 한다
        await().alias("처리한 메시지는 지워져 다시 처리되지 않는다")
                .during(Duration.ofSeconds(8)).atMost(TIMEOUT)
                .until(() -> dispatchCount(body) == 1);
        assertThat(threads.get(body)).isEqualTo("preorder-events-0");
    }

    /** 같은 메시지가 다시 전달돼도(SQS 는 최소 1회 전달) 상태 전이는 한 번이다. */
    @Test
    void 같은_이벤트가_두_번_와도_한_번만_반영된다() {
        AcceptResult accepted = new AcceptFixtures(acceptService, fixtures, catalogClient).accept(fixtures.customer());
        UUID preorderId = accepted.preorder().id();
        String externalNumber = "R-" + ShopFixtures.unique();
        String body = externalJobSucceeded(ShopFixtures.unique(), fixtures.workerSucceeds(preorderId, "REGISTER"),
                AcceptFixtures.tokenOf(accepted), externalNumber);

        queues.send("preorder-events", body);
        queues.send("preorder-events", body);

        // 받은 횟수는 처리 도중에도 오르므로, 두 번째 처리가 끝난 뒤에 확인한다
        await().alias("두 메시지를 모두 끝까지 처리한다").atMost(TIMEOUT)
                .until(() -> handled.getOrDefault(body, 0) == 2);
        assertThat(status(preorderId)).isEqualTo("REGISTERED");
        assertThat(fixtures.count("""
                SELECT COUNT(*) FROM preorder_events WHERE preorder_id = ? AND to_status = 'REGISTERED'
                """, (Object) UuidBinary.toBytes(preorderId))).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT external_reference FROM preorders WHERE id = ?",
                String.class, (Object) UuidBinary.toBytes(preorderId))).isEqualTo(externalNumber);
    }

    @Test
    void 처리하지_못하는_메시지는_다시_받다가_DLQ_를_거쳐_DB_로_옮겨진다() {
        String poison = "not-json-" + ShopFixtures.unique();

        queues.send("preorder-events", poison);

        await().alias("%d 번 받고도 처리하지 못하면 DLQ, DLQ 소비기가 DB 로 옮긴다"
                        .formatted(FlociTestContainer.MAX_RECEIVE_COUNT))
                .atMost(Duration.ofSeconds(30))
                .until(() -> fixtures.count("""
                        SELECT COUNT(*) FROM preorder_dead_letter_events
                         WHERE body = ? AND status = 'OPEN' AND failure_reason = 'UNREADABLE_BODY'
                        """, poison) == 1);
        assertThat(queues.receive("preorder-events-dlq", m -> m.body().equals(poison), Duration.ofSeconds(3)))
                .as("옮긴 메시지는 DLQ 에서 지운다").isEmpty();
        assertThat(threads.get(poison)).isEqualTo("preorder-events-dlq-0");
    }

    /** 빈 큐의 롱 폴링은 대기 시간만큼 걸린다. 클라이언트 기본 제한 시간에 걸려 받기가 실패하면 안 된다. */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 롱_폴링이_호출_제한_시간에_걸리지_않는다(CapturedOutput output) {
        await().during(Duration.ofSeconds(8)).atMost(Duration.ofSeconds(10))
                .until(() -> !output.getOut().contains("큐를 받지 못했다"));
    }

    /** 흐름 ①: 접수가 등록 요청을 발행하고, worker 대역이 성공을 알리면 예약이 결제 가능이 된다. */
    @Test
    void 접수부터_외부_등록_성공까지_SQS_로_이어져_결제_가능이_된다() {
        AcceptResult accepted = new AcceptFixtures(acceptService, fixtures, catalogClient).accept(fixtures.customer());
        UUID preorderId = accepted.preorder().id();
        String token = AcceptFixtures.tokenOf(accepted);

        Message registerJobReady = queues.receive("preorder-register", m -> m.body().contains(token), TIMEOUT)
                .orElseThrow();
        UUID syncJobId = UUID.fromString(
                jsonMapper.readTree(registerJobReady.body()).get("payload").get("syncJobId").asString());
        assertThat(syncJobId).isEqualTo(fixtures.workerSucceeds(preorderId, "REGISTER"));

        queues.send("preorder-events",
                externalJobSucceeded(ShopFixtures.unique(), syncJobId, token, "R-" + ShopFixtures.unique()));

        await().atMost(TIMEOUT).until(() -> "REGISTERED".equals(status(preorderId)));
    }

    private String externalJobSucceeded(String eventId, UUID syncJobId, String token, String externalNumber) {
        return """
                {"eventId":"%s","eventType":"EXTERNAL_JOB_SUCCEEDED","aggregateType":"PREORDER_SYNC_JOB",
                 "aggregateId":"%s","occurredAt":"2026-09-03T01:00:03.470Z",
                 "payload":{"syncJobId":"%s","preorderId":"%s","jobType":"REGISTER","externalNumber":"%s"}}
                """.formatted(eventId, syncJobId, syncJobId, token, externalNumber);
    }

    /** 받은 횟수(호출 기록은 진입 시점). 끝까지 처리했는지는 handled 로 본다. */
    private long dispatchCount(String body) {
        return mockingDetails(dispatcher).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("dispatch"))
                .filter(invocation -> body.equals(invocation.getArgument(0)))
                .count();
    }

    private String status(UUID preorderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM preorders WHERE id = ?", String.class,
                (Object) UuidBinary.toBytes(preorderId));
    }
}
