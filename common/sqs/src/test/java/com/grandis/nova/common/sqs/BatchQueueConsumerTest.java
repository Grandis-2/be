package com.grandis.nova.common.sqs;

import com.grandis.nova.common.sqs.testing.TestQueues;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 실제 SQS 프로토콜(Floci)로 빈 자리만큼 받기 · 동시 처리 · 묶음 삭제 · 돌려보내기 · 받기 멈춤 · 지연 재전송을 본다.
 * 시험 메서드끼리 큐를 나눠 쓰므로 본문에 시험마다 새 id 를 싣고, 처리 함수는 본문 머리로 동작을 고른다.
 */
class BatchQueueConsumerTest {

    static final String QUEUE = "test-batch-events";
    static final String ATTEMPT = "attempt";
    static final String LAST_OUTCOME = "lastOutcome";
    static final String EVENT_TYPE = "eventType";
    static final Duration TIMEOUT = Duration.ofSeconds(15);
    static final Duration VISIBILITY = Duration.ofSeconds(3);
    static final BatchConsumerSettings SETTINGS =
            new BatchConsumerSettings(QUEUE, 1, VISIBILITY, Duration.ofSeconds(10));

    SqsClient sqs;
    TestQueues queues;
    DelayedResend resend;
    BatchQueueConsumer consumer;
    /** 동시 처리 상한. 빈 자리 수 = 상한 − 처리 중. */
    final AtomicInteger limit = new AtomicInteger(10);
    final AtomicInteger concurrent = new AtomicInteger();
    final AtomicInteger maxConcurrent = new AtomicInteger();
    /** 본문 → 처리 함수가 받은 시각들. */
    final Map<String, List<Instant>> seen = new ConcurrentHashMap<>();
    /** 본문 → 마지막으로 받은 메시지 속성. */
    final Map<String, Map<String, String>> attributes = new ConcurrentHashMap<>();
    final CountDownLatch holdEntered = new CountDownLatch(1);
    /** 빈 자리 수를 물은 횟수. 받기 스레드 하나가 앞선 받기가 돌아온 뒤에야 다시 묻는다. */
    final AtomicInteger slotQueries = new AtomicInteger();
    /** 켜면 다음에 빈 자리 수를 물을 때 받기를 멈춘다 — 그 순간에는 진행 중인 받기가 없다. */
    final AtomicBoolean pauseOnNextQuery = new AtomicBoolean();
    final CountDownLatch pausedBetweenReceives = new CountDownLatch(1);

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
        SqsQueueUrls queueUrls = new SqsQueueUrls(sqs, properties);
        resend = new DelayedResend(sqs, queueUrls, QUEUE);
        consumer = new BatchQueueConsumer(sqs, queueUrls, SETTINGS, List.of(ATTEMPT, LAST_OUTCOME, EVENT_TYPE),
                this::freeSlots, this::handle);
        consumer.start();
    }

    @AfterEach
    void tearDown() {
        consumer.stop();
        sqs.close();
    }

    @Test
    void 처리하면_지워_다시_처리하지_않는다() {
        List<String> bodies = IntStream.range(0, 5).mapToObj(i -> "ok-" + UUID.randomUUID()).toList();

        bodies.forEach(body -> queues.send(QUEUE, body));

        await().atMost(TIMEOUT).until(() -> seen.keySet().containsAll(bodies));
        // 지우지 못했다면 가시성 시간(3s) 뒤 다시 보여 한 번 더 처리된다
        await().during(Duration.ofSeconds(5)).atMost(TIMEOUT)
                .until(() -> bodies.stream().allMatch(body -> count(body) == 1));
    }

    @Test
    void 빈_자리가_없으면_받지_않고_생기면_받는다() {
        limit.set(0);
        // 자리가 있을 때 시작한 롱 폴링이 돌아와야 다시 묻는다 — 이 뒤로 물으면 자리 0 을 본 것이다
        int queries = slotQueries.get();
        await().atMost(TIMEOUT).until(() -> slotQueries.get() > queries);
        String body = "ok-" + UUID.randomUUID();

        queues.send(QUEUE, body);

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4)).until(() -> count(body) == 0);
        limit.set(1);
        await().atMost(TIMEOUT).until(() -> count(body) == 1);
    }

    @Test
    void 빈_자리_수를_넘기지_않고_동시에_처리한다() {
        limit.set(3);
        List<String> bodies = IntStream.range(0, 12).mapToObj(i -> "slow-" + UUID.randomUUID()).toList();

        bodies.forEach(body -> queues.send(QUEUE, body));

        await().atMost(TIMEOUT).until(() -> seen.keySet().containsAll(bodies));
        assertThat(maxConcurrent.get()).as("한 묶음 안에서도 동시에 처리하되 빈 자리 수는 넘기지 않는다").isBetween(2, 3);
    }

    @Test
    void 돌려보내면_가시성_시간을_기다리지_않고_다시_받는다() {
        String body = "return-" + UUID.randomUUID();

        queues.send(QUEUE, body);

        await().atMost(TIMEOUT).until(() -> count(body) == 2);
        assertThat(gap(body)).isLessThan(VISIBILITY.minusMillis(500));
    }

    @Test
    void 그대로_두면_가시성_시간_뒤_다시_받는다() {
        String body = "keep-" + UUID.randomUUID();

        queues.send(QUEUE, body);

        await().atMost(TIMEOUT).until(() -> count(body) == 2);
        assertThat(gap(body)).isGreaterThanOrEqualTo(VISIBILITY.minusMillis(500));
    }

    @Test
    void 처리_함수가_던지면_그대로_두어_가시성_시간_뒤_다시_받는다() {
        String body = "fail-" + UUID.randomUUID();

        queues.send(QUEUE, body);

        await().atMost(TIMEOUT).until(() -> count(body) == 2);
        assertThat(gap(body)).isGreaterThanOrEqualTo(VISIBILITY.minusMillis(500));
        assertThat(consumer.isRunning()).isTrue();
        assertThat(consumer.inFlight()).isZero();
    }

    @Test
    void 받기를_멈추면_받지_않고_재개하면_받는다() throws Exception {
        pauseOnNextQuery.set(true);
        assertThat(pausedBetweenReceives.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        String body = "ok-" + UUID.randomUUID();

        queues.send(QUEUE, body);

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4)).until(() -> count(body) == 0);
        assertThat(consumer.isPaused()).isTrue();
        consumer.resume();
        await().atMost(TIMEOUT).until(() -> count(body) == 1);
    }

    @Test
    void 멈출_때_처리_중인_메시지를_마치고_지운다() throws Exception {
        String body = "hold-" + UUID.randomUUID();
        queues.send(QUEUE, body);
        assertThat(holdEntered.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        consumer.stop();

        assertThat(count(body)).isEqualTo(1);
        assertThat(consumer.isRunning()).isFalse();
        assertThat(consumer.inFlight()).isZero();
        assertThat(queues.receive(QUEUE, m -> m.body().equals(body), VISIBILITY.plusSeconds(2)))
                .as("지웠으므로 가시성 시간이 지나도 다시 보이지 않는다").isEmpty();
    }

    @Test
    void 지연_재전송은_본문과_받은_속성을_잇고_바꾼_속성을_싣는다() {
        String body = "resend-" + UUID.randomUUID();

        sqs.sendMessage(request -> request.queueUrl(sqs.getQueueUrl(r -> r.queueName(QUEUE)).queueUrl())
                .messageBody(body)
                .messageAttributes(Map.of(ATTEMPT, text("1"), EVENT_TYPE, text("REGISTER_JOB_READY"))));

        await().atMost(TIMEOUT).until(() -> count(body) == 2);
        assertThat(attributes.get(body)).containsOnly(Map.entry(ATTEMPT, "2"), Map.entry(LAST_OUTCOME, "UNKNOWN"),
                Map.entry(EVENT_TYPE, "REGISTER_JOB_READY"));
        assertThat(gap(body)).as("DelaySeconds 1초").isGreaterThanOrEqualTo(Duration.ofMillis(900));
    }

    private int freeSlots() {
        slotQueries.incrementAndGet();
        if (pauseOnNextQuery.compareAndSet(true, false)) {
            consumer.pause();
            pausedBetweenReceives.countDown();
            return 0;
        }
        return limit.get() - consumer.inFlight();
    }

    private MessageOutcome handle(Message message) {
        int now = concurrent.incrementAndGet();
        maxConcurrent.accumulateAndGet(now, Math::max);
        try {
            String body = message.body();
            Map<String, String> received = new HashMap<>();
            message.messageAttributes().forEach((name, value) -> received.put(name, value.stringValue()));
            attributes.put(body, received);
            int times = seen.computeIfAbsent(body, key -> new CopyOnWriteArrayList<>()).size() + 1;
            seen.get(body).add(Instant.now());
            if (times == 1 && body.startsWith("return-")) {
                return MessageOutcome.returnAfter(Duration.ZERO);
            }
            if (times == 1 && body.startsWith("keep-")) {
                return MessageOutcome.keep();
            }
            if (times == 1 && body.startsWith("fail-")) {
                throw new IllegalStateException("처리 실패");
            }
            if (body.startsWith("resend-") && "1".equals(received.get(ATTEMPT))) {
                Map<String, String> changes = new HashMap<>();
                changes.put(ATTEMPT, "2");
                changes.put(LAST_OUTCOME, "UNKNOWN");
                resend.resend(message, changes, Duration.ofSeconds(1));
            }
            if (body.startsWith("slow-")) {
                sleep(Duration.ofMillis(300));
            }
            if (body.startsWith("hold-")) {
                holdEntered.countDown();
                sleep(Duration.ofSeconds(1));
            }
            return MessageOutcome.delete();
        } finally {
            concurrent.decrementAndGet();
        }
    }

    private int count(String body) {
        return seen.getOrDefault(body, List.of()).size();
    }

    /** 첫 번째와 두 번째로 받은 시각의 차이. */
    private Duration gap(String body) {
        List<Instant> times = seen.get(body);
        return Duration.between(times.get(0), times.get(1));
    }

    private static MessageAttributeValue text(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
