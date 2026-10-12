package com.grandis.nova.worker.sync;

import com.grandis.nova.common.sqs.BatchQueueConsumer;
import com.grandis.nova.common.sqs.MessageOutcome;
import com.grandis.nova.common.sqs.testing.SqsTestConfig;
import com.grandis.nova.common.sqs.testing.TestQueues;
import com.grandis.nova.worker.mock.FakeMock;
import io.micrometer.core.instrument.MeterRegistry;
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
import software.amazon.awssdk.services.sqs.SqsClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.grandis.nova.worker.mock.FakeMock.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 내려갈 때(배포 · Spot 회수). 외부를 부르고 있던 작업은 결과를 알리고 지운 뒤에 내려가고, 그 뒤에 받은 작업은 부르지 않고
 * 같은 속성으로 큐에 남긴다 — 다른 worker 가 기다리지 않고 받는다.
 */
@SpringBootTest(properties = {
        "nova.sqs.region=ap-northeast-2",
        "nova.worker.consumer.wait-seconds=2",
        // 등록 응답을 시험이 풀어 줄 때까지 붙잡아 둔다
        "nova.worker.mock.connect-timeout=4s",
        "nova.worker.mock.read-timeout=15s",
        "nova.worker.mock.slow-call-threshold=14s",
        "nova.worker.circuit.window=100",
        "nova.worker.circuit.minimum-calls=100"})
@Import(SqsTestConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ShutdownFlowTest {

    static final FakeMock MOCK = new FakeMock();
    static final Map<String, String> SECOND_TURN =
            Map.of("run", "2", "attempt", "2", "generation", "1", "lastOutcome", "TRANSIENT_FAILURE", "actor", "ADMIN");

    @Autowired
    ApplicationContext context;

    @Autowired
    SqsClient sqs;

    @Autowired
    TestQueues queues;

    @Autowired
    @Qualifier("registerConsumer")
    BatchQueueConsumer registerConsumer;

    @Autowired
    @Qualifier("cancelConsumer")
    BatchQueueConsumer cancelConsumer;

    @Autowired
    @Qualifier("registerJobHandler")
    JobHandler handler;

    @Autowired
    ShutdownSignal shutdown;

    @Autowired
    MeterRegistry meters;

    @DynamicPropertySource
    static void mock(DynamicPropertyRegistry registry) {
        registry.add("nova.worker.mock.base-url", () -> MOCK.baseUrl().toString());
    }

    @AfterAll
    static void closeMock() {
        MOCK.close();
    }

    @Test
    void 내려갈_때_부르던_작업은_끝까지_마치고_그_뒤에_받은_작업은_부르지_않고_큐에_남긴다() throws Exception {
        CountDownLatch answered = new CountDownLatch(1);
        MOCK.respond(exchange -> {
            answered.await(12, TimeUnit.SECONDS);
            json(201, "{\"externalKey\":\"K\",\"externalNumber\":\"R-1\",\"status\":\"ACTIVE\"}").handle(exchange);
        });
        Jobs calling = new Jobs();
        Jobs late = new Jobs();
        Jobs lateCancel = Jobs.cancel();
        Jobs afterStop = new Jobs();
        send(calling, Map.of());
        await().atMost(Duration.ofSeconds(10)).until(() -> !calling.calls(MOCK).isEmpty());

        CompletableFuture<Void> stopping = CompletableFuture.runAsync(this::stop);
        try {
            await().atMost(Duration.ofSeconds(5)).until(shutdown::stopping);
            send(late, SECOND_TURN);
            send(lateCancel, SECOND_TURN);
            assertThat(stopping).as("부르던 작업이 끝나기를 기다린다").isNotDone();
        } finally {
            answered.countDown();
        }
        stopping.get(30, TimeUnit.SECONDS);

        assertThat(late.calls(MOCK)).as("내려가는 중에 받은 작업은 부르지 않는다").isEmpty();
        assertThat(late.resent(queues)).as("차례를 쓰지 않고 그대로 남긴다").containsAllEntriesOf(SECOND_TURN);
        // 취소 소비기는 등록 소비기가 멈추기를 기다리는 동안에도 살아 있다. 받고 돌려보내기를 되풀이하면 안 된다
        assertThat(lateCancel.calls(MOCK)).isEmpty();
        assertThat(returned("CANCEL")).as("받고 있던 수신이 돌려준 한 번까지만").isLessThanOrEqualTo(1);
        assertThat(lateCancel.resent(queues)).containsAllEntriesOf(SECOND_TURN);
        assertThat(calling.event(queues).get("eventType").asString()).isEqualTo("EXTERNAL_JOB_SUCCEEDED");
        assertThat(calling.resent(queues, Duration.ofSeconds(2))).as("끝낸 작업은 지웠다").isEmpty();

        double before = returned("REGISTER");
        assertThat(handler.handle(afterStop.message(SECOND_TURN))).isEqualTo(MessageOutcome.delete());
        assertThat(afterStop.calls(MOCK)).isEmpty();
        assertThat(afterStop.resent(queues)).as("받은 횟수를 쓰지 않게 새 메시지로 돌려준다")
                .containsAllEntriesOf(SECOND_TURN);
        assertThat(returned("REGISTER") - before).isEqualTo(1);
    }

    private double returned(String jobType) {
        return meters.counter(JobHandler.JOBS_METRIC, "jobType", jobType, "outcome", "returned").count();
    }

    /** 컨텍스트가 닫힐 때의 순서 그대로: 닫힘을 알리고, 소비기를 멈춘다. 컨텍스트 자체는 시험 틀이 닫는다. */
    private void stop() {
        context.publishEvent(new ContextClosedEvent(context));
        registerConsumer.stop();
        cancelConsumer.stop();
    }

    private void send(Jobs job, Map<String, String> attributes) {
        String url = sqs.getQueueUrl(request -> request.queueName(job.queue())).queueUrl();
        sqs.sendMessage(request -> request.queueUrl(url).messageBody(job.body())
                .messageAttributes(job.attributes(attributes)));
    }
}
