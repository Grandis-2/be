package com.grandis.nova.preorder.integration.sqs;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.sqs.testing.TestQueues;
import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.support.SqsIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * worker 가 결과를 알리지 못해 작업 메시지가 DLQ 로 가면, preorder 가 그 DLQ 를 받아 작업을 DEAD_LETTER 로 둔다(Floci).
 * 그래야 관리자 재처리 · 수동 완료로 넘어간다 — 그대로 두면 작업이 PENDING 으로 남아 아무도 모른다.
 */
@SqsIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class JobDeadLetterConsumerTest {

    static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    TestQueues queues;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    MeterRegistry meterRegistry;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @Test
    void 등록_작업_메시지가_DLQ_로_가면_작업을_DEAD_LETTER_로_두고_DLQ_에서_지운다() {
        Message job = registerJobReady();
        UUID syncJobId = syncJobIdOf(job);
        double before = failed("REGISTER");

        queues.send(JobDeadLetterConsumerConfig.REGISTER_DLQ, job.body());

        await().atMost(TIMEOUT).until(() -> "DEAD_LETTER".equals(jobStatus(syncJobId)));
        assertThat(failed("REGISTER") - before).isEqualTo(1);
        await().atMost(TIMEOUT).until(() -> queues.messagesIn(JobDeadLetterConsumerConfig.REGISTER_DLQ) == 0);
    }

    /** 관리자 재처리로 실행 회차가 오른 작업이다. 지난 실행의 메시지가 늦게 DLQ 로 와도 다시 포기시키지 않는다. */
    @Test
    void 지난_실행의_메시지면_작업을_바꾸지_않고_지운다() {
        Message job = registerJobReady();
        UUID syncJobId = syncJobIdOf(job);
        jdbcTemplate.update("UPDATE preorder_sync_jobs SET run = 2 WHERE id = ?", (Object) UuidBinary.toBytes(syncJobId));

        queues.send(JobDeadLetterConsumerConfig.REGISTER_DLQ, job.body());

        await().atMost(TIMEOUT).until(() -> queues.messagesIn(JobDeadLetterConsumerConfig.REGISTER_DLQ) == 0);
        assertThat(jobStatus(syncJobId)).isEqualTo("PENDING");
    }

    /**
     * 어느 작업인지 모르면 바꿀 것이 없다. 지우면 흔적이 사라지므로 DLQ 에 남겨 DLQ 경보로 사람이 보게 한다.
     * 소비기가 가시성 시간마다 다시 받으므로, 같은 메시지를 두 번 받았으면 지우지 않은 것이다.
     */
    @Test
    void 어느_작업인지_읽을_수_없는_메시지는_지우지_않는다(CapturedOutput output) {
        String body = "not-json-" + UUID.randomUUID();

        queues.send(JobDeadLetterConsumerConfig.CANCEL_DLQ, body);

        await().atMost(TIMEOUT).until(() -> unreadable(output).distinct().count() == 1
                && unreadable(output).count() >= 2);
        assertThat(queues.messagesIn(JobDeadLetterConsumerConfig.CANCEL_DLQ)).isPositive();
    }

    /** 이 시험이 넣은 메시지의 "읽을 수 없다" 로그에 찍힌 messageId 들. */
    private static Stream<String> unreadable(CapturedOutput output) {
        return output.getOut().lines().filter(line -> line.contains("어느 작업인지 읽을 수 없는"))
                .map(line -> line.substring(line.indexOf("messageId=")));
    }

    private Message registerJobReady() {
        AcceptResult accepted = new AcceptFixtures(acceptService, fixtures, catalogClient).accept(fixtures.customer());
        String token = AcceptFixtures.tokenOf(accepted);
        return queues.receive("preorder-register", message -> message.body().contains(token), TIMEOUT).orElseThrow();
    }

    private UUID syncJobIdOf(Message message) {
        return UUID.fromString(jsonMapper.readTree(message.body()).get("payload").get("syncJobId").asString());
    }

    private String jobStatus(UUID syncJobId) {
        return jdbcTemplate.queryForObject("SELECT status FROM preorder_sync_jobs WHERE id = ?", String.class,
                (Object) UuidBinary.toBytes(syncJobId));
    }

    private double failed(String jobType) {
        return meterRegistry.counter("preorder.sync.failed", "jobType", jobType, "reason", "DEAD_LETTER_QUEUE").count();
    }
}
