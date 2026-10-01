package com.grandis.nova.common.sqs;

import com.grandis.nova.common.sqs.testing.FlociTestContainer;
import com.grandis.nova.common.sqs.testing.TestQueues;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 실제 SQS 프로토콜(Floci)로 받기 · 지우기 · 다시 보이기 · DLQ 를 본다. 소비기가 받은 메시지를 지우므로 이 시험만 쓰는 큐를 둔다.
 * 시험 메서드끼리는 큐를 나눠 쓰므로 본문에 시험마다 새 id 를 싣고, 처리 함수는 본문 머리(fail · error · slow)로 동작을 고른다.
 */
@ExtendWith(OutputCaptureExtension.class)
class RetryingQueueConsumerTest {

    static final String QUEUE = "test-consumer-events";
    static final String MARK = "mark";
    static final Duration TIMEOUT = Duration.ofSeconds(15);
    /** 롱 폴링(5s)이 클라이언트 기본 제한 시간(3s)보다 길다 — 받기가 제한 시간에 걸리지 않는지 함께 본다. */
    static final QueuePollerSettings SETTINGS = new QueuePollerSettings(QUEUE, 1, 5, 10,
            Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofSeconds(1));

    SqsClient sqs;
    TestQueues queues;
    RetryingQueueConsumer consumer;
    final Queue<String> handled = new ConcurrentLinkedQueue<>();
    /** 처리 함수가 본 메시지 속성(본문 → mark 값). */
    final Map<String, String> marks = new ConcurrentHashMap<>();
    final CountDownLatch slowEntered = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        SqsClientProperties properties = FlociSqs.properties(Map.of());
        sqs = SqsClient.builder()
                .region(Region.of(properties.region()))
                .endpointOverride(properties.endpoint())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(config -> config.apiCallTimeout(properties.apiCallTimeout()))
                .build();
        queues = new TestQueues(sqs);
        consumer = new RetryingQueueConsumer(sqs, new SqsQueueUrls(sqs, properties), SETTINGS, List.of(MARK), message -> {
            String body = message.body();
            if (message.messageAttributes().containsKey(MARK)) {
                marks.put(body, message.messageAttributes().get(MARK).stringValue());
            }
            if (body.startsWith("fail-")) {
                throw new IllegalStateException("처리 실패");
            }
            if (body.startsWith("error-")) {
                throw new AssertionError("깨진 메시지");
            }
            if (body.startsWith("slow-")) {
                slowEntered.countDown();
                // 가시성 시간(2s) 안에 끝낸다 — 끝난 뒤의 수신 핸들로 지우는 동작은 에뮬레이터마다 다르다
                sleep(Duration.ofSeconds(1));
            }
            handled.add(body);
        });
        consumer.start();
    }

    @AfterEach
    void tearDown() {
        consumer.stop();
        sqs.close();
    }

    @Test
    void 처리하면_지워_다시_처리하지_않는다() {
        String body = "ok-" + UUID.randomUUID();

        queues.send(QUEUE, body);

        await().atMost(TIMEOUT).until(() -> handled.contains(body));
        // 지우지 못했다면 가시성 시간(2s) 뒤 다시 보여 한 번 더 처리된다 — 그 몇 배를 기다려도 한 번이어야 한다
        await().during(Duration.ofSeconds(6)).atMost(TIMEOUT).until(() -> count(body) == 1);
    }

    /** 보내는 쪽이 실은 속성(DLQ 되돌리기 표식 등)은 받을 이름으로 적은 것만 처리 함수에 닿는다. */
    @Test
    void 적어_둔_메시지_속성이_처리_함수에_닿는다() {
        String body = "ok-" + UUID.randomUUID();

        sqs.sendMessage(request -> request.queueUrl(sqs.getQueueUrl(r -> r.queueName(QUEUE)).queueUrl())
                .messageBody(body)
                .messageAttributes(Map.of(MARK, MessageAttributeValue.builder()
                        .dataType("Number").stringValue("42").build())));

        await().atMost(TIMEOUT).until(() -> handled.contains(body));
        assertThat(marks).containsEntry(body, "42");
    }

    @Test
    void 처리하지_못하면_다시_받다가_DLQ_로_간다() {
        String body = "fail-" + UUID.randomUUID();

        queues.send(QUEUE, body);

        assertThat(queues.receive(QUEUE + "-dlq", m -> m.body().equals(body), Duration.ofSeconds(30)))
                .as("%d 번 받고도 처리하지 못하면 DLQ", FlociTestContainer.MAX_RECEIVE_COUNT)
                .isPresent();
    }

    /** 독이 든 메시지 하나가 작업 스레드를 끝내면 큐 전체가 멈춘다. 그 한 건만 실패로 두고 다음 메시지를 받는다. */
    @Test
    void 처리_중_Error_가_나도_다음_메시지를_받고_그_메시지는_DLQ_로_간다() {
        String poison = "error-" + UUID.randomUUID();
        String next = "ok-" + UUID.randomUUID();

        queues.send(QUEUE, poison);
        queues.send(QUEUE, next);

        await().atMost(TIMEOUT).until(() -> handled.contains(next));
        assertThat(consumer.isRunning()).isTrue();
        assertThat(queues.receive(QUEUE + "-dlq", m -> m.body().equals(poison), Duration.ofSeconds(30))).isPresent();
    }

    @Test
    void 롱_폴링이_호출_제한_시간에_걸리지_않는다(CapturedOutput output) {
        await().during(Duration.ofSeconds(8)).atMost(Duration.ofSeconds(10))
                .until(() -> !output.getOut().contains("큐를 받지 못했다"));
    }

    @Test
    void 멈출_때_받은_묶음은_마저_처리하고_지운다() throws Exception {
        String body = "slow-" + UUID.randomUUID();
        queues.send(QUEUE, body);
        assertThat(slowEntered.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        consumer.stop();

        assertThat(handled).contains(body);
        assertThat(consumer.isRunning()).isFalse();
        assertThat(queues.receive(QUEUE, m -> m.body().equals(body), Duration.ofSeconds(4)))
                .as("지웠으므로 가시성 시간이 지나도 다시 보이지 않는다").isEmpty();
    }

    private long count(String body) {
        return handled.stream().filter(body::equals).count();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
