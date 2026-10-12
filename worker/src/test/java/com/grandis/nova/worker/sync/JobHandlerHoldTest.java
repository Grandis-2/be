package com.grandis.nova.worker.sync;

import com.grandis.nova.common.sqs.BatchQueueConsumer;
import com.grandis.nova.common.sqs.testing.SqsTestConfig;
import com.grandis.nova.common.sqs.testing.TestQueues;
import com.grandis.nova.worker.mock.FakeMock;
import com.grandis.nova.worker.mock.MockCircuit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.grandis.nova.worker.mock.FakeMock.after;
import static com.grandis.nova.worker.mock.FakeMock.html;
import static com.grandis.nova.worker.mock.FakeMock.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 받았지만 지금 부를 수 없는 메시지. 차례를 쓰지 않고 속성 그대로 큐로 돌려보내야 한다 — 호출하지 않았는데 횟수가 줄면
 * mock 이 내려가 있는 동안 작업들이 한 번도 불러 보지 못하고 포기된다.
 */
@SpringBootTest(properties = {
        "nova.sqs.region=ap-northeast-2",
        "nova.worker.mock.connect-timeout=2s",
        "nova.worker.mock.read-timeout=2s",
        "nova.worker.mock.slow-call-threshold=1500ms",
        "nova.worker.retry.in-round-delay-min=10ms",
        "nova.worker.retry.in-round-delay-max=20ms",
        "nova.worker.retry.backoff=1s,1s,1s",
        "nova.worker.retry.jitter-ratio=0",
        // 최근 2건이 모두 실패하면 2초 동안 연다
        "nova.worker.circuit.window=2",
        "nova.worker.circuit.minimum-calls=2",
        "nova.worker.circuit.open-first=2s",
        "nova.worker.circuit.open-max=2s",
        // 자리 2 중 하나는 CANCEL 전용이라 등록은 한 번에 하나만 나간다
        "nova.worker.concurrency.per-mock=2",
        "nova.worker.concurrency.initial=2",
        "nova.worker.concurrency.min=2"})
@Import(SqsTestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class JobHandlerHoldTest {

    static final FakeMock MOCK = new FakeMock();
    static final Map<String, String> THIRD_TURN =
            Map.of("run", "2", "attempt", "3", "generation", "2", "lastOutcome", "TRANSIENT_FAILURE", "actor", "ADMIN");

    @Autowired
    @Qualifier("registerJobHandler")
    JobHandler handler;

    @Autowired
    @Qualifier("cancelJobHandler")
    JobHandler cancelHandler;

    @Autowired
    MockCircuit circuit;

    @Autowired
    TestQueues queues;

    /** 소비기가 큐를 받지 않게 대역으로 바꾼다. 처리기는 시험이 직접 부른다. */
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
    void 차단기가_열려_있으면_부르지_않고_차례를_쓰지_않은_채_다시_보낸다() {
        MOCK.respond(html(503));
        Jobs failing = new Jobs();
        handler.handle(failing.message(Map.of()));
        assertThat(circuit.isOpen()).as("한 차례의 두 번 실패로 열렸다").isTrue();
        failing.event(queues);
        failing.resent(queues);
        Jobs held = new Jobs();

        handler.handle(held.message(THIRD_TURN));

        assertThat(held.calls(MOCK)).isEmpty();
        Map<String, String> resent = held.resent(queues);
        assertThat(resent).containsAllEntriesOf(THIRD_TURN);
        assertThat(held.event(queues, Duration.ofSeconds(2))).as("시도로 세지 않는다").isEmpty();
    }

    /** 부른 뒤에 열린 것이다. 이미 한 번 불렀으므로 차례를 쓴 것으로 치고 그 실패를 기록한다. */
    @Test
    void 차례_도중에_차단기가_열리면_곧바로_다시_보내지_않고_그_실패로_차례를_끝낸다() {
        MOCK.respond(html(504));
        Jobs unknown = new Jobs();
        handler.handle(unknown.message(Map.of()));
        unknown.event(queues);
        unknown.resent(queues);
        assertThat(circuit.isOpen()).as("결과 모름은 곧바로 다시 보내지 않아 실패가 한 건뿐이다").isFalse();
        MOCK.respond(html(503));
        Jobs job = new Jobs();

        handler.handle(job.message(Map.of()));

        assertThat(circuit.isOpen()).isTrue();
        assertThat(job.calls(MOCK)).hasSize(1);
        assertThat(job.event(queues).get("eventType").asString()).isEqualTo("EXTERNAL_JOB_ATTEMPTED");
        assertThat(job.resent(queues)).containsEntry("attempt", "2").containsEntry("lastOutcome", "TRANSIENT_FAILURE");
    }

    @Test
    void 자리를_얻지_못하면_부르지_않고_차례를_쓰지_않은_채_다시_보낸다() throws Exception {
        MOCK.respond(after(Duration.ofMillis(800),
                json(201, "{\"externalKey\":\"K\",\"externalNumber\":\"R-1\",\"status\":\"ACTIVE\"}")));
        Jobs running = new Jobs();
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> handler.handle(running.message(Map.of())));
        await().atMost(Duration.ofSeconds(5)).until(() -> !running.calls(MOCK).isEmpty());
        Jobs held = new Jobs();

        handler.handle(held.message(THIRD_TURN));

        assertThat(held.calls(MOCK)).as("앞선 호출이 자리를 쥐고 있다").isEmpty();
        assertThat(held.resent(queues)).containsAllEntriesOf(THIRD_TURN);
        first.get(10, TimeUnit.SECONDS);
        assertThat(running.event(queues).get("eventType").asString()).isEqualTo("EXTERNAL_JOB_SUCCEEDED");
    }

    /** 취소는 꼭 반영돼야 한다 — 등록이 몰려도 전용 자리 하나로 나간다. */
    @Test
    void 등록이_자리를_다_써도_취소는_전용_자리로_나간다() throws Exception {
        CountDownLatch registered = new CountDownLatch(1);
        MOCK.respond(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/cancellations")) {
                json(200, "{\"externalNumbers\":[\"R-1\"]}").handle(exchange);
            } else {
                registered.await(1500, TimeUnit.MILLISECONDS);
                json(201, "{\"externalKey\":\"K\",\"externalNumber\":\"R-1\",\"status\":\"ACTIVE\"}").handle(exchange);
            }
        });
        Jobs running = new Jobs();
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> handler.handle(running.message(Map.of())));
        await().atMost(Duration.ofSeconds(5)).until(() -> !running.calls(MOCK).isEmpty());
        Jobs cancel = Jobs.cancel();

        try {
            cancelHandler.handle(cancel.message(Map.of()));

            assertThat(first).as("등록은 아직 자리를 쥐고 있다").isNotDone();
            assertThat(cancel.calls(MOCK)).containsExactly("POST /external/cancellations");
        } finally {
            registered.countDown();
        }
        assertThat(cancel.event(queues).get("eventType").asString()).isEqualTo("EXTERNAL_JOB_SUCCEEDED");
        first.get(10, TimeUnit.SECONDS);
    }
}
