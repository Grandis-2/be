package com.grandis.nova.order.draw;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.cart.domain.repository.CartStore;
import com.grandis.nova.order.client.catalog.CatalogClient;
import com.grandis.nova.order.client.catalog.CatalogOption;
import com.grandis.nova.order.client.catalog.CatalogOptions;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.TestAuth;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 시간 예산을 실제 DB 로 — 재고 행을 다른 트랜잭션이 쥐고 있어 회차 만들기가 잠금을 기다리는 동안 예산(시험 2초)을 넘기면, 잠금 대기가 예산 안에서
 * 끊기고 회차 · 확보가 남지 않는다. 사진 주소를 읽은 생성이 예산 밖에서 커밋하지 못한다는 계약의 "잠금 대기" 갈래다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "nova.draw.create-budget=PT2S")
@DisplayName("드로우 회차 만들기 — 재고 잠금을 기다리다 예산을 넘기면 롤백")
class AdminDrawBudgetLockWaitTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean CatalogClient catalogClient;
    @MockitoSpyBean CartStore store;

    @Test
    @DisplayName("재고 행을 쥔 트랜잭션이 안 놓으면 예산(2초) 근처에서 409 retryable — 회차 0, 놓은 뒤 확보 0")
    void lockWaitBeyondBudgetRollsBack() throws Exception {
        OrderFixtures fixtures = new OrderFixtures(jdbcTemplate);
        OrderFixtures.StockProduct product = fixtures.inStockProduct(1);
        UUID option = product.optionIds().getFirst();
        fixtures.stock(option, 10, 0, 0);
        given(catalogClient.getOptions(any(), any())).willReturn(ApiResponse.ok(new CatalogOptions(List.of(new CatalogOption(option,
                product.productId(), "굿즈", "블랙", "SKU", new BigDecimal("50000"), "ACTIVE", "IN_STOCK", "ACTIVE", false, true,
                new CatalogOption.Warranty(false, BigDecimal.ZERO), "https://img/products/gift.jpg")))));

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.queryForList("SELECT option_id FROM option_inventories WHERE option_id = ? FOR UPDATE", (Object) bytes(option));
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        try {
            Instant opens = Instant.now().minusSeconds(60);
            String body = """
                    {"productId":"%s","optionId":"%s","title":"드로우","entryFee":100,"winnerCount":1,"opensAt":"%s","closesAt":"%s"}"""
                    .formatted(product.productId(), option, opens, Instant.now().plus(Duration.ofDays(1)));
            long start = System.nanoTime();
            MockHttpServletResponse response = mockMvc.perform(post("/api/v1/admin/draws").with(TestAuth.admin())
                    .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            System.out.println("=== lock wait: status " + response.getStatus() + " after " + elapsedMs + "ms body " + response.getContentAsString());

            assertThat(response.getStatus()).isEqualTo(409);
            JsonNode error = JSON.readTree(response.getContentAsString()).get("error");
            assertThat(error.get("code").asString()).isEqualTo("STATE_CONFLICT");
            assertThat(error.get("details").get("retryable").asBoolean()).isTrue();
            assertThat(elapsedMs).as("잠금 대기 기본값(50초)이 아니라 예산 근처에서 끊긴다").isBetween(1_500L, 4_000L);
        } finally {
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM draw_campaigns WHERE option_id = ?", Long.class, (Object) bytes(option)))
                .isZero();
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_reserved FROM option_inventories WHERE option_id = ?", Integer.class, (Object) bytes(option))).isZero());
    }
}
