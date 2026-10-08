package com.grandis.nova.catalog.outbox;

import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.registration.ProductRegistrationRequest;
import com.grandis.nova.catalog.registration.ProductRegistrationService;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.sqs.testing.FlociTestContainer;
import com.grandis.nova.common.sqs.testing.SqsTestConfig;
import com.grandis.nova.common.sqs.testing.TestQueues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 SQS 프로토콜(Floci) 위에서 등록 → 아웃박스 → 큐. 받는 쪽(preorder · order)이 푸는 봉투 모양과 메시지 속성까지 본다.
 * 기본 시험 설정(전송 = 로그)을 SQS 로 덮는다 — 덮이지 않으면 큐에 아무것도 안 들어와 시험이 실패한다.
 */
@CatalogIntegrationTest
@TestPropertySource(properties = {"nova.outbox.transport=sqs", "nova.sqs.region=" + FlociTestContainer.REGION})
@Import(SqsTestConfig.class)
class OutboxSqsFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired ProductRegistrationService registrations;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TestQueues queues;

    @Test
    @DisplayName("일반 상품 등록은 order-events 로, 사전예약 등록은 preorder-events 로 봉투 하나씩 간다")
    void registrationEventsReachTheirQueues() {
        UUID categoryId = new ShopFixtures(jdbcTemplate).category();
        UUID inStock = registrations.register("k-" + ShopFixtures.unique(), new ProductRegistrationRequest(categoryId, SaleMode.IN_STOCK,
                "케이블", null, null, true, new BigDecimal("9000"), null, null,
                List.of(new ProductRegistrationRequest.Combination(Map.of(), false, null, 3)), null, null, null))
                .registration().productId();
        Instant opensAt = Instant.now().plus(Duration.ofHours(1));
        UUID preorder = registrations.register("k-" + ShopFixtures.unique(), new ProductRegistrationRequest(categoryId, SaleMode.PREORDER,
                "Nova", null, null, false, new BigDecimal("1000"), null, null,
                List.of(new ProductRegistrationRequest.Combination(Map.of(), false, null, null)), null,
                new ProductRegistrationRequest.Campaign(opensAt, opensAt.plus(Duration.ofDays(1))),
                List.of(new ProductRegistrationRequest.ShipmentBatch(1, 1L, null, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7)))))
                .registration().productId();

        Message toOrder = receiveFor("order-events", inStock);
        JsonNode stockEnvelope = JSON.readTree(toOrder.body());
        assertThat(stockEnvelope.get("eventType").asString()).isEqualTo("IN_STOCK_PRODUCT_REGISTERED");
        assertThat(stockEnvelope.get("aggregateType").asString()).isEqualTo("PRODUCT");
        assertThat(stockEnvelope.get("eventId").asString()).hasSize(36);
        assertThat(stockEnvelope.get("occurredAt").asString()).isNotBlank();
        assertThat(stockEnvelope.get("payload").get("items").get(0).get("stockTotal").asInt()).isEqualTo(3);
        assertThat(stockEnvelope.get("payload").has("productId")).isFalse();
        assertThat(toOrder.messageAttributes().get("eventType").stringValue()).isEqualTo("IN_STOCK_PRODUCT_REGISTERED");
        assertThat(toOrder.messageAttributes().get("eventId").stringValue()).isEqualTo(stockEnvelope.get("eventId").asString());

        JsonNode campaignEnvelope = JSON.readTree(receiveFor("preorder-events", preorder).body());
        assertThat(campaignEnvelope.get("eventType").asString()).isEqualTo("PREORDER_PRODUCT_REGISTERED");
        assertThat(campaignEnvelope.get("payload").get("shipmentBatches")).hasSize(1);

        assertThat(jdbcTemplate.queryForList("SELECT published_at FROM catalog_outbox_events WHERE aggregate_id IN (?, ?)",
                Object.class, UuidBinary.toBytes(inStock), UuidBinary.toBytes(preorder))).as("둘 다 발행 완료로 표시").hasSize(2).doesNotContainNull();
    }

    /** 그 상품의 메시지만 받아 지운다. 큐를 시험끼리 공유하므로 다른 메시지는 건드리지 않는다. */
    private Message receiveFor(String queue, UUID productId) {
        return queues.receive(queue, message -> JSON.readTree(message.body()).get("aggregateId").asString().equals(productId.toString()), TIMEOUT)
                .orElseThrow(() -> new AssertionError(queue + " 에 상품 " + productId + " 의 메시지가 오지 않았다"));
    }
}
