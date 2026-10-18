package com.grandis.nova.catalog.event;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * order 의 판매 준비 이벤트(IN_STOCK_PRODUCT_READY)를 실제 SQS 프로토콜(Floci)로 받아 상품을 판매 준비로 남긴다. 재고 행은 이벤트가 닿기 전 상태로
 * 넣는다(inventoryOnly) — 판매 준비가 재고 표가 아니라 이벤트로 정해지는지 본다.
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "nova.sqs.region=" + FlociTestContainer.REGION,
        "nova.sqs.consumer.enabled=true",
        "nova.sqs.consumer.wait-seconds=1",
        "nova.sqs.consumer.visibility=2s",
        "nova.sqs.consumer.backoff-base=1s",
        "nova.sqs.consumer.backoff-max=1s"
})
@Import(SqsTestConfig.class)
class InStockProductReadyFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String QUEUE = "catalog-events";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired TestQueues queues;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MockMvc mockMvc;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @Test
    @DisplayName("이벤트를 받으면 판매 준비가 되어 목록 · 상세에 나온다 — 재고 행만으로는 준비가 아니다. 같은 이벤트가 다시 와도 처음 시각 그대로")
    void readyEventExposesProduct() throws Exception {
        String tag = "r" + ShopFixtures.unique();
        UUID product = fixtures.product(fixtures.category(), "IN_STOCK", "ACTIVE", "준비 " + tag, tag);
        UUID option = fixtures.option(product, "ACTIVE", ShopFixtures.OPTION_PRICE);
        fixtures.inventoryOnly(option, 3, 0, 0);

        assertThat(listed(tag)).as("이벤트 전 — 재고 행이 있어도 준비 전").isZero();
        mockMvc.perform(get("/api/v1/products/{id}", product)).andExpect(status().isNotFound());

        queues.send(QUEUE, body(product));
        await(() -> readyAt(product) != null, "판매 준비가 채워지지 않았다");
        await(() -> queues.messagesIn(QUEUE) == 0, "처리한 메시지가 큐에서 지워지지 않았다");
        Timestamp first = readyAt(product);

        assertThat(listed(tag)).isEqualTo(1);
        mockMvc.perform(get("/api/v1/products/{id}", product)).andExpect(status().isOk());

        queues.send(QUEUE, body(product));
        await(() -> queues.messagesIn(QUEUE) == 0, "다시 온 메시지가 지워지지 않았다");
        assertThat(readyAt(product)).as("다시 와도 처음 시각").isEqualTo(first);
    }

    @Test
    @DisplayName("없는 상품의 이벤트는 실패로 올려 재수신 한도 뒤 DLQ 로 간다 — 계약 위반을 조용히 버리지 않는다")
    void unknownProductGoesToDeadLetterQueue() {
        UUID missing = UUID.randomUUID();

        queues.send(QUEUE, body(missing));

        assertThat(queues.receive(QUEUE + "-dlq", message -> message.body().contains(missing.toString()), TIMEOUT)).isPresent();
    }

    /** 회원 목록 검색에 나오는 수(q 는 태그 부분 일치). */
    private long listed(String tag) throws Exception {
        String body = mockMvc.perform(get("/api/v1/products").param("q", tag)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("data").get("total").asLong();
    }

    private Timestamp readyAt(UUID product) {
        return jdbcTemplate.queryForObject("SELECT stock_ready_at FROM products WHERE id = ?", Timestamp.class, (Object) UuidBinary.toBytes(product));
    }

    private static String body(UUID productId) {
        return JSON.writeValueAsString(new EventEnvelope(UUID.randomUUID().toString(), InStockProductReadyHandler.EVENT_TYPE,
                InStockProductReadyHandler.AGGREGATE_TYPE, productId, Instant.now(), JSON.readTree("{}")));
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
