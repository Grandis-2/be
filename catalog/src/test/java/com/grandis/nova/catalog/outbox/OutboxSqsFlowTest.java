package com.grandis.nova.catalog.outbox;

import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.registration.ProductRegistrationRequest;
import com.grandis.nova.catalog.registration.ProductRegistrationService;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.FlociQueues;
import com.grandis.nova.catalog.support.ShopFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 SQS 프로토콜(Floci) 위에서 등록 → 아웃박스 → 큐. 받는 쪽(preorder · order)이 푸는 봉투 모양과 메시지 속성까지 본다.
 * 기본 시험 설정(전송 = 로그)을 SQS 로 덮는다 — 덮이지 않으면 큐에 아무것도 안 들어와 시험이 실패한다.
 */
@CatalogIntegrationTest
@TestPropertySource(properties = {"nova.outbox.transport=sqs", "nova.sqs.region=" + FlociQueues.REGION})
@Import(OutboxSqsFlowTest.FlociProperties.class)
class OutboxSqsFlowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static SqsClient queues;

    @Autowired ProductRegistrationService registrations;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void openClient() {
        queues = FlociQueues.client();
    }

    @AfterAll
    static void closeClient() {
        queues.close();
    }

    @Test
    @DisplayName("일반 상품 등록은 order-events 로, 사전예약 등록은 preorder-events 로 봉투 하나씩 간다")
    void registrationEventsReachTheirQueues() {
        Long categoryId = new ShopFixtures(jdbcTemplate).category();
        Long inStock = registrations.register("k-" + ShopFixtures.unique(), new ProductRegistrationRequest(categoryId, SaleMode.IN_STOCK,
                "케이블", null, null, true, new BigDecimal("9000"), null, null,
                List.of(new ProductRegistrationRequest.Combination(Map.of(), false, null, null, 3)), null, null, null))
                .registration().productId();
        Instant opensAt = Instant.now().plus(Duration.ofHours(1));
        Long preorder = registrations.register("k-" + ShopFixtures.unique(), new ProductRegistrationRequest(categoryId, SaleMode.PREORDER,
                "Nova", null, null, false, new BigDecimal("1000"), null, null,
                List.of(new ProductRegistrationRequest.Combination(Map.of(), false, null, null, null)), null,
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
                Object.class, inStock, preorder)).as("둘 다 발행 완료로 표시").hasSize(2).doesNotContainNull();
    }

    /** 그 상품의 메시지가 올 때까지 받는다. 다른 시험이 남긴 메시지는 건너뛰고 지운다. */
    private static Message receiveFor(String queue, Long productId) {
        String url = queues.getQueueUrl(request -> request.queueName(queue)).queueUrl();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            List<Message> messages = queues.receiveMessage(request -> request.queueUrl(url).waitTimeSeconds(2).maxNumberOfMessages(10)
                    .messageAttributeNames("All").messageSystemAttributeNames(MessageSystemAttributeName.ALL)).messages();
            for (Message message : messages) {
                queues.deleteMessage(request -> request.queueUrl(url).receiptHandle(message.receiptHandle()));
                if (JSON.readTree(message.body()).get("aggregateId").asLong() == productId) {
                    return message;
                }
            }
        }
        throw new AssertionError(queue + " 에 상품 " + productId + " 의 메시지가 오지 않았다");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FlociProperties {

        @Bean
        DynamicPropertyRegistrar flociEndpoint() {
            return registry -> {
                registry.add("nova.sqs.endpoint", () -> FlociQueues.get().getEndpoint());
                registry.add("nova.sqs.access-key", () -> FlociQueues.get().getAccessKey());
                registry.add("nova.sqs.secret-key", () -> FlociQueues.get().getSecretKey());
            };
        }
    }
}
