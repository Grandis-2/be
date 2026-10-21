package com.grandis.nova.catalog.event;

import com.grandis.nova.catalog.integration.PublicCampaigns;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.message.EventEnvelope;
import com.grandis.nova.common.sqs.testing.FlociTestContainer;
import com.grandis.nova.common.sqs.testing.SqsTestConfig;
import com.grandis.nova.common.sqs.testing.TestQueues;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * preorder 의 회차 일정 변경 이벤트(PREORDER_CAMPAIGN_SCHEDULE_CHANGED)를 실제 SQS 프로토콜(Floci)로 받아 공개 회차 캐시를 비운다. 공개 회차 캐시를 켠다.
 * preorder 의 회차 변경은 시험 DB 의 회차 행을 직접 고쳐 흉내 낸다(대역 클라이언트가 그 행을 읽는다).
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "nova.read-cache.enabled=true",
        "nova.read-cache.preorder-campaign-ttl=1h",
        "nova.sqs.region=" + FlociTestContainer.REGION,
        "nova.sqs.consumer.enabled=true",
        "nova.sqs.consumer.wait-seconds=1",
        "nova.sqs.consumer.visibility=2s",
        "nova.sqs.consumer.backoff-base=1s",
        "nova.sqs.consumer.backoff-max=1s"
})
@Import(SqsTestConfig.class)
class PreorderCampaignScheduleChangedFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String QUEUE = "catalog-events";
    private static final String GENERATION = "catalog:cache:gen:" + PublicCampaigns.CAMPAIGNS;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired TestQueues queues;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redis;
    @Autowired MockMvc mockMvc;
    @Autowired PreorderCampaignScheduleChangedHandler handler;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @Test
    @DisplayName("일정 변경을 받으면 회차 캐시 세대가 올라 조기 마감이 상세에 바로 보이고 메시지는 지워진다 — 받기 전에는 캐시 값")
    void scheduleChangeClearsCampaignCache() throws Exception {
        UUID product = fixtures.product(fixtures.category(), "PREORDER", "ACTIVE", "일정 변경", "t");
        fixtures.registration(product);
        fixtures.option(product, "ACTIVE");
        Instant now = Instant.now();
        fixtures.campaign(product, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));
        assertThat(detailStatus(product)).isEqualTo("OPEN");

        jdbcTemplate.update("UPDATE preorder_campaigns SET closes_at = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE WHERE product_id = ?",
                (Object) UuidBinary.toBytes(product));   // preorder 가 조기 마감했다
        assertThat(detailStatus(product)).as("받기 전에는 캐시 값").isEqualTo("OPEN");

        queues.send(QUEUE, body(product));
        await(() -> "CLOSED".equals(detailStatus(product)), "일정 변경을 받았는데 상세가 마감으로 바뀌지 않았다");
        await(() -> queues.messagesIn(QUEUE) == 0, "처리한 메시지가 큐에서 지워지지 않았다");
        assertThat(queues.receive(QUEUE + "-dlq", message -> message.body().contains(product.toString()), Duration.ofSeconds(3)))
                .as("처리돼 지워졌다 — 실패로 DLQ 에 간 것이 아니다").isEmpty();
    }

    @Test
    @DisplayName("세대를 못 올리면 던진다(메시지를 다시 받는다) — 올라가면 끝난다")
    void failedBumpThrows() {
        redis.opsForValue().set(GENERATION, "not-a-number");   // INCR 이 확실히 실패하게
        try {
            assertThatThrownBy(() -> handler.handle(UUID.randomUUID(), null)).isInstanceOf(IllegalStateException.class);
        } finally {
            redis.delete(GENERATION);
        }
        handler.handle(UUID.randomUUID(), null);
        assertThat(redis.opsForValue().get(GENERATION)).isNotNull();
    }

    private String detailStatus(UUID product) {
        try {
            String body = mockMvc.perform(get("/api/v1/products/{id}", product)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            return JSON.readTree(body).get("data").get("campaign").get("status").asString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String body(UUID productId) {
        return JSON.writeValueAsString(new EventEnvelope(UUID.randomUUID().toString(), PreorderCampaignScheduleChangedHandler.EVENT_TYPE,
                PreorderCampaignScheduleChangedHandler.AGGREGATE_TYPE, productId, Instant.now(),
                JSON.readTree("{\"productId\":\"" + productId + "\",\"scheduleVersion\":2,\"change\":\"CLOSED\"}")));
    }

    private static void await(BooleanSupplier condition, String failure) throws InterruptedException {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError(failure);
            }
            Thread.sleep(100);
        }
    }
}
