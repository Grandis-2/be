package com.grandis.nova.preorder.campaign;

import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@PreorderIntegrationTest
@AutoConfigureMockMvc
class ShipmentBatchApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @Test
    void 로그인_없이_차수를_번호_순으로_본다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();

        mockMvc.perform(get("/api/v1/preorders/products/{productId}/shipment-batches", product.productId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].batchNumber").value(1))
                .andExpect(jsonPath("$.data.items[0].positionFrom").value(1))
                .andExpect(jsonPath("$.data.items[0].positionTo").value(ShopFixtures.FIRST_BATCH_LAST_POSITION))
                .andExpect(jsonPath("$.data.items[0].estimatedShipStart").value("2026-11-01"))
                .andExpect(jsonPath("$.data.items[1].batchNumber").value(2))
                .andExpect(jsonPath("$.data.items[1].positionTo").doesNotExist());
    }

    @Test
    void 차수가_없는_상품은_404() throws Exception {
        UUID productId = fixtures.product("IN_STOCK", "ACTIVE");

        mockMvc.perform(get("/api/v1/preorders/products/{productId}/shipment-batches", productId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
    }
}
