package com.grandis.nova.worker.sync;

import com.grandis.nova.common.sqs.BatchQueueConsumer;
import com.grandis.nova.common.sqs.MessageOutcome;
import com.grandis.nova.common.sqs.testing.SqsTestConfig;
import com.grandis.nova.common.sqs.testing.TestQueues;
import com.grandis.nova.worker.mock.FakeMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.grandis.nova.worker.mock.FakeMock.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 자리를 기다리는 사이에 내려가기 시작한 메시지. 받을 때는 내려가는 중이 아니었지만, 자리를 얻은 뒤에 새 호출을 시작하면
 * 종료 시간을 넘길 수 있다 — 부르지 않고 돌려준다. 큐 소비기는 대역으로 바꾸고 처리기를 직접 부른다.
 */
@SpringBootTest(properties = {
        "nova.sqs.region=ap-northeast-2",
        "nova.worker.mock.connect-timeout=4s",
        "nova.worker.mock.read-timeout=15s",
        "nova.worker.mock.slow-call-threshold=14s",
        // 자리 2 중 하나는 CANCEL 전용이라 등록은 한 번에 하나만 나간다. 두 번째는 자리를 오래 기다린다
        "nova.worker.concurrency.per-mock=2",
        "nova.worker.concurrency.initial=2",
        "nova.worker.concurrency.min=2",
        "nova.worker.concurrency.permit-wait=10s"})
@Import(SqsTestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ShutdownWhileWaitingTest {

    static final FakeMock MOCK = new FakeMock();
    static final Map<String, String> SECOND_TURN =
            Map.of("run", "2", "attempt", "2", "generation", "1", "lastOutcome", "TRANSIENT_FAILURE", "actor", "ADMIN");

    @Autowired
    ApplicationContext context;

    @Autowired
    @Qualifier("registerJobHandler")
    JobHandler handler;

    @Autowired
    TestQueues queues;

    @MockitoBean
    BatchQueueConsumer registerConsumer;

    @MockitoBean
    BatchQueueConsumer cancelConsumer;

    @DynamicPropertySource
    static void mock(DynamicPropertyRegistry registry) {
        registry.add("nova.worker.mock.base-url", () -> MOCK.baseUrl().toString());
    }

    @AfterAll
    static void closeMock() {
        MOCK.close();
    }

    @Test
    void 자리를_기다리는_사이에_내려가기_시작하면_자리를_얻어도_부르지_않고_돌려준다() throws Exception {
        CountDownLatch answered = new CountDownLatch(1);
        MOCK.respond(exchange -> {
            answered.await(12, TimeUnit.SECONDS);
            json(201, "{\"externalKey\":\"K\",\"externalNumber\":\"R-1\",\"status\":\"ACTIVE\"}").handle(exchange);
        });
        Jobs calling = new Jobs();
        Jobs waiting = new Jobs();
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> handler.handle(calling.message(Map.of())));
        await().atMost(Duration.ofSeconds(10)).until(() -> !calling.calls(MOCK).isEmpty());
        AtomicReference<MessageOutcome> outcome = new AtomicReference<>();
        Thread second = Thread.ofPlatform().start(() -> outcome.set(handler.handle(waiting.message(SECOND_TURN))));

        try {
            // 처리기가 시간을 두고 기다리는 곳은 자리뿐이다
            await().atMost(Duration.ofSeconds(5)).until(() -> second.getState() == Thread.State.TIMED_WAITING);
            context.publishEvent(new ContextClosedEvent(context));
        } finally {
            answered.countDown();
        }
        first.get(10, TimeUnit.SECONDS);
        second.join(Duration.ofSeconds(10));

        assertThat(outcome).hasValue(MessageOutcome.delete());
        assertThat(waiting.calls(MOCK)).as("자리를 얻었지만 부르지 않는다").isEmpty();
        assertThat(waiting.resent(queues)).as("차례를 쓰지 않고 그대로 돌려준다").containsAllEntriesOf(SECOND_TURN);
        assertThat(calling.event(queues).get("eventType").asString()).isEqualTo("EXTERNAL_JOB_SUCCEEDED");
    }
}
