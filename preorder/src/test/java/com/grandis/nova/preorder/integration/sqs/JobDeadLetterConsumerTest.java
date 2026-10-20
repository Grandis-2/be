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
    void 등록_작업_메시지가_DLQ_로_가면_작업을_DEAD_LETTER_로_두고_DLQ_에서_지운다(CapturedOutput output) {
        Message job = registerJobReady();
        UUID syncJobId = syncJobIdOf(job);
        double before = failed("REGISTER");

        queues.send(JobDeadLetterConsumerConfig.REGISTER_DLQ, job.body());

        await().atMost(TIMEOUT).until(() -> "DEAD_LETTER".equals(jobStatus(syncJobId)));
        assertThat(failed("REGISTER") - before).isEqualTo(1);
        // 지우지 않았으면 가시성 시간(2초) 뒤 다시 받아 "이미 끝난 작업" 으로 남긴다
        String again = "이미 끝난 작업의 메시지가 DLQ 로 갔다 — 작업은 그대로 둔다 syncJobId=" + syncJobId;
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(8))
                .until(() -> !output.getOut().contains(again));
    }

    /** 관리자 재처리로 실행 회차가 오른 작업이다. 지난 실행의 메시지가 늦게 DLQ 로 와도 다시 포기시키지 않는다. */
    @Test
    void 지난_실행의_메시지면_작업을_바꾸지_않고_지운다(CapturedOutput output) {
        Message job = registerJobReady();
        UUID syncJobId = syncJobIdOf(job);
        jdbcTemplate.update("UPDATE preorder_sync_jobs SET run = 2 WHERE id = ?",
                (Object) UuidBinary.toBytes(syncJobId));

        queues.send(JobDeadLetterConsumerConfig.REGISTER_DLQ, job.body());

        String stale = "지난 실행의 작업 메시지가 DLQ 로 갔다 — 작업은 그대로 둔다 syncJobId=" + syncJobId;
        await().atMost(TIMEOUT).until(() -> output.getOut().contains(stale));
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(8))
                .until(() -> output.getOut().lines().filter(line -> line.contains(stale)).count() == 1);
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
    }

    /** 지우면 흔적이 사라진다. 작업이 지워졌거나 다른 환경의 메시지일 수 있어 사람이 본다. */
    @Test
    void 없는_작업의_메시지는_지우지_않는다(CapturedOutput output) {
        UUID syncJobId = UUID.randomUUID();

        queues.send(JobDeadLetterConsumerConfig.REGISTER_DLQ, """
                {"eventId":"%s","eventType":"REGISTER_JOB_READY","aggregateType":"PREORDER_SYNC_JOB","aggregateId":"%s",
                 "occurredAt":"2026-10-20T01:00:00Z","payload":{"syncJobId":"%s"}}""".formatted(UUID.randomUUID(),
                syncJobId, syncJobId));

        String kept = "작업에 반영할 수 없는 DLQ 메시지다(없는 작업 · 앞선 실행 회차) — 지우지 않는다";
        await().atMost(TIMEOUT).until(() -> output.getOut().lines()
                .filter(line -> line.contains(kept) && line.contains("syncJobId=" + syncJobId)).count() >= 2);
    }

    /**
     * 재처리로 실행 회차가 오른 작업이다. 회차를 읽을 수 없는 메시지를 첫 실행으로 읽으면 지난 실행으로 보고 지워 작업이 PENDING 에
     * 남는다 — 지우지 않고 DLQ 에 남겨 사람이 본다.
     */
    @Test
    void 실행_회차를_읽을_수_없는_메시지는_지난_실행으로_보지_않고_지우지_않는다(CapturedOutput output) {
        Message job = registerJobReady();
        UUID syncJobId = syncJobIdOf(job);
        jdbcTemplate.update("UPDATE preorder_sync_jobs SET run = 2 WHERE id = ?",
                (Object) UuidBinary.toBytes(syncJobId));
        String unreadableRun = job.body().replace("\"run\":1", "\"run\":\"2\"");
        assertThat(unreadableRun).as("payload 의 실행 회차를 문자열로 바꿨다").contains("\"run\":\"2\"");

        queues.send(JobDeadLetterConsumerConfig.REGISTER_DLQ, unreadableRun);

        // 지웠으면 가시성 시간(2초) 뒤 다시 받지 않는다 — 두 번 남았으면 지우지 않은 것이다
        String kept = "실행 회차를 읽을 수 없는 작업 DLQ 메시지다 — 지우지 않는다";
        await().atMost(TIMEOUT).until(() -> output.getOut().lines()
                .filter(line -> line.contains(kept) && line.contains("syncJobId=" + syncJobId)).count() >= 2);
        assertThat(output.getOut()).doesNotContain("지난 실행의 작업 메시지가 DLQ 로 갔다 — 작업은 그대로 둔다 syncJobId=" + syncJobId);
        assertThat(jobStatus(syncJobId)).isEqualTo("PENDING");
    }

    /** 작업보다 앞선 실행 회차는 정상 흐름에서 생기지 않는다. 계약이 어긋난 것이라 작업을 바꾸지 않고 메시지를 남긴다. */
    @Test
    void 작업보다_앞선_실행_회차의_메시지면_작업을_바꾸지_않고_지우지_않는다(CapturedOutput output) {
        Message job = registerJobReady();
        UUID syncJobId = syncJobIdOf(job);
        String future = job.body().replace("\"run\":1", "\"run\":2");
        assertThat(future).as("payload 의 실행 회차를 올렸다").contains("\"run\":2");

        queues.send(JobDeadLetterConsumerConfig.REGISTER_DLQ, future);

        String contract = "계약 확인 필요, 작업은 그대로 둔다 syncJobId=" + syncJobId;
        await().atMost(TIMEOUT)
                .until(() -> output.getOut().lines().filter(line -> line.contains(contract)).count() >= 2);
        assertThat(jobStatus(syncJobId)).isEqualTo("PENDING");
    }

    private static Stream<String> unreadable(CapturedOutput output) {
        return logged(output, "어느 작업인지 읽을 수 없는");
    }

    /** 이 시험이 넣은 메시지에 대해 그 로그가 찍힌 messageId 들. 소비기가 다시 받을 때마다 한 줄씩 는다. */
    private static Stream<String> logged(CapturedOutput output, String text) {
        return output.getOut().lines().filter(line -> line.contains(text))
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
