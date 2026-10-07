package com.grandis.nova.waitingroom.schedule;

import com.grandis.nova.common.message.EventEnvelope;
import com.grandis.nova.common.sqs.testing.FlociTestContainer;
import com.grandis.nova.common.sqs.testing.SqsTestConfig;
import com.grandis.nova.common.sqs.testing.TestQueues;
import com.grandis.nova.waitingroom.control.ScheduleResyncRequester;
import com.grandis.nova.waitingroom.domain.product.SalesWindow;
import com.grandis.nova.waitingroom.redis.ProductSchedules;
import com.grandis.nova.waitingroom.redis.RedisKeys;
import com.grandis.nova.waitingroom.support.RedisContainer;
import com.grandis.nova.waitingroom.support.TestJwts;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.grandis.nova.waitingroom.support.TestIds.productKey;
import static org.assertj.core.api.Assertions.assertThat;

/** 실제 큐(Floci)로 회차 일정을 받아 반영하고, 재발행 요청을 preorder 의 소비 큐에 계약 모양대로 보낸다. */
@SpringBootTest(properties = {"waitingroom.control.enabled=false", "jwt.jwk-set-uri=",
        "waitingroom.token.secret=" + TestJwts.TOKEN_SECRET, "nova.sqs.region=" + FlociTestContainer.REGION,
        "waitingroom.schedule.wait-seconds=1"})
@Import(SqsTestConfig.class)
class ScheduleMessagingTest {

    static final Duration WAIT = Duration.ofSeconds(15);
    static final SalesWindow WINDOW = new SalesWindow(Instant.parse("2026-10-10T01:00:00Z"), Instant.parse("2026-10-10T02:00:00Z"));
    static final SalesWindow MOVED = new SalesWindow(Instant.parse("2026-10-11T01:00:00Z"), Instant.parse("2026-10-11T02:00:00Z"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("jwt.issuer", () -> TestJwts.ISSUER);
        registry.add("jwt.public-keys." + TestJwts.KID, TestJwts::publicKeyPem);
        registry.add("spring.data.redis.host", RedisContainer::host);
        registry.add("spring.data.redis.port", RedisContainer::port);
    }

    @Autowired
    TestQueues queues;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    ScheduleResyncRequester resyncRequester;

    @Autowired
    MeterRegistry registry;

    ReactiveStringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        redis = RedisContainer.fresh();
    }

    @Test
    void 일정_이벤트를_받아_반영하고_늦게_온_옛_일정은_버린다() {
        queues.send("waitingroom-events", changed(productKey(501), 3, MOVED));
        awaitSchedule(productKey(501), ProductSchedules.format(MOVED, 3, true));

        double staleBefore = staleEvents();
        queues.send("waitingroom-events", changed(productKey(501), 2, WINDOW));
        StepVerifier.create(Flux.interval(Duration.ofMillis(100)).filter(tick -> staleEvents() > staleBefore).next())
                .expectNextCount(1).expectComplete().verify(WAIT);

        assertThat(schedule(productKey(501))).as("옛 번호는 받고 버린다").isEqualTo(ProductSchedules.format(MOVED, 3, true));
    }

    @Test
    void 비공개_일정을_받으면_비공개로_반영하고_칸이_없으면_공개로_본다() {
        queues.send("waitingroom-events", changed(productKey(502), 1, WINDOW, false));
        awaitSchedule(productKey(502), ProductSchedules.format(WINDOW, 1, false));

        queues.send("waitingroom-events", changed(productKey(502), 2, WINDOW));
        awaitSchedule(productKey(502), ProductSchedules.format(WINDOW, 2, true));
    }

    @Test
    void 읽을_수_없는_메시지는_지우지_않고_다시_받는다() {
        double failedBefore = failedEvents();
        queues.send("waitingroom-events", "{not json");

        // 처음 받아 실패하고, 백오프 뒤 다시 보여 한 번 더 받는다
        StepVerifier.create(Flux.interval(Duration.ofMillis(100)).filter(tick -> failedEvents() >= failedBefore + 2).next())
                .expectNextCount(1).expectComplete().verify(Duration.ofSeconds(30));
    }

    @Test
    void 재발행_요청은_preorder_소비_큐에_봉투와_속성을_실어_보낸다() {
        resyncRequester.request("SCHEDULE_EMPTY").block(WAIT);

        Message message = queues.receive("preorder-events", received -> "CAMPAIGN_RESYNC_REQUESTED".equals(
                received.messageAttributes().get("eventType").stringValue()), WAIT).orElseThrow();
        EventEnvelope envelope = jsonMapper.readValue(message.body(), EventEnvelope.class);

        assertThat(envelope.eventType()).isEqualTo("CAMPAIGN_RESYNC_REQUESTED");
        assertThat(envelope.aggregateType()).isEqualTo("PREORDER_CAMPAIGN");
        assertThat(envelope.aggregateId()).isNull();
        assertThat(envelope.eventId()).isEqualTo(message.messageAttributes().get("eventId").stringValue());
        assertThat(envelope.payload().path("requestedBy").asString()).isEqualTo("waitingroom");
        assertThat(envelope.payload().path("reason").asString()).isEqualTo("SCHEDULE_EMPTY");
        assertThat(resyncRequester.maxRequestTime()).as("주소 조회 + 전송, 호출마다 기본 시한 3초").isEqualTo(Duration.ofSeconds(6));
    }

    private String changed(String productId, long version, SalesWindow window) {
        return changed(productId, version, window, null);
    }

    private String changed(String productId, long version, SalesWindow window, Boolean visible) {
        Map<String, Object> payload = new HashMap<>(Map.of("productId", productId, "scheduleVersion", version,
                "opensAt", window.opensAt().toString(), "closesAt", window.closesAt().toString(),
                "changedAt", Instant.now().toString(), "change", visible == null ? "RESCHEDULED" : "VISIBILITY"));
        if (visible != null) {
            payload.put("visible", visible);
        }
        return jsonMapper.writeValueAsString(new EventEnvelope(UUID.randomUUID().toString(), "PREORDER_CAMPAIGN_CHANGED",
                "PREORDER_CAMPAIGN", UUID.fromString(productId), Instant.now(), jsonMapper.valueToTree(payload)));
    }

    private double failedEvents() {
        Counter failed = registry.find("waitingroom.schedule.events").tag("outcome", "FAILED").counter();
        return failed == null ? 0 : failed.count();
    }

    private double staleEvents() {
        Counter stale = registry.find("waitingroom.schedule.events").tag("outcome", "STALE").counter();
        return stale == null ? 0 : stale.count();
    }

    private String schedule(String productId) {
        return (String) redis.opsForHash().get(RedisKeys.PRODUCTS, productId).block(Duration.ofSeconds(5));
    }

    private void awaitSchedule(String productId, String expected) {
        StepVerifier.create(Flux.interval(Duration.ofMillis(100))
                        .concatMap(tick -> redis.opsForHash().get(RedisKeys.PRODUCTS, productId).defaultIfEmpty(""))
                        .filter(expected::equals)
                        .next())
                .expectNextCount(1).expectComplete().verify(WAIT);
    }
}
