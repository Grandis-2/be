package com.grandis.nova.catalog.integration;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 내부 API 주소(base-url)가 비어 있으면 그 서비스가 답하지 않는 것과 같다(application.yml.example) — 연결할 곳이 없는 호출이 500 이 아니라 일시 장애로
 * 강등된다. 시험용 재고 · 회차 대역을 끄고 order · order-stocks · preorder 주소를 비운다.
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"nova.test.fake-order-stocks=false", "nova.test.fake-preorder-campaigns=false",
        "spring.http.serviceclient.order.base-url=", "spring.http.serviceclient.order-stocks.base-url=", "spring.http.serviceclient.preorder.base-url="})
@DisplayName("내부 API 주소가 비면 그 서비스 장애와 같다 — 목록 200(품절 표시 · 사전예약 없이), 일반 상세 200(수량 null), 사전예약 상세 503")
class MissingBaseUrlTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("목록 · 일반 상세는 200 으로 강등되고, 사전예약 상세는 결정대로 503 이다")
    void missingAddressIsUnavailable() throws Exception {
        ShopFixtures fixtures = new ShopFixtures(jdbcTemplate);
        UUID category = fixtures.category();
        UUID inStock = fixtures.product(category, "IN_STOCK", "ACTIVE", "주소 없음 일반", "mb");
        fixtures.option(inStock, "ACTIVE", new BigDecimal("1000"));
        fixtures.markStockReady(inStock);
        UUID preorder = fixtures.product(category, "PREORDER", "ACTIVE", "주소 없음 사전예약", "mb");
        fixtures.option(preorder, "ACTIVE", new BigDecimal("1000"));

        mockMvc.perform(get("/api/v1/products").param("categoryId", category.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].soldOut").value(false));
        mockMvc.perform(get("/api/v1/products/ranking")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/products/{id}", inStock)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.variants[0].availableQuantity").isEmpty());
        mockMvc.perform(get("/api/v1/products/{id}", preorder)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"));
    }
}
