package com.grandis.nova.preorder.campaign;

import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static com.grandis.nova.preorder.support.AccessTokens.admin;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 결과를 잠깐 캐시하므로 시험마다 새 상품을 쓰고, 상태를 다 만든 뒤에 한 번만 묻는다. */
@PreorderIntegrationTest
@AutoConfigureMockMvc
class ProductProgressApiTest {

    static final String PATH = "/api/v1/preorders/products/{productId}/progress";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    Campaigns campaigns;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @Test
    void 로그인_없이_접수_상태와_지금_신청하면_배정될_차수를_보고_접수_수는_싣지_않는다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();

        mockMvc.perform(get(PATH, product.productId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(product.productId().toString()))
                .andExpect(jsonPath("$.data.saleStatus").value("OPEN"))
                .andExpect(jsonPath("$.data.nextBatch.batchNumber").value(1))
                .andExpect(jsonPath("$.data.nextBatch.positionTo").value(ShopFixtures.FIRST_BATCH_LAST_POSITION))
                .andExpect(jsonPath("$.data.nextBatch.estimatedShipStart").value("2026-11-01"))
                .andExpect(jsonPath("$.data.countedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.issuedCount").doesNotExist());
    }

    /** 첫 차수를 순번 1 까지로 두어 한 건만 접수해도 다음 차수로 넘어가게 한다. */
    @Test
    void 첫_차수가_다_차면_다음_차수를_알려_준다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct(1);
        new AcceptFixtures(acceptService, fixtures, catalogClient).accept(fixtures.customer(), product);

        mockMvc.perform(get(PATH, product.productId()))
                .andExpect(jsonPath("$.data.saleStatus").value("OPEN"))
                .andExpect(jsonPath("$.data.nextBatch.batchNumber").value(2))
                .andExpect(jsonPath("$.data.nextBatch.positionFrom").value(2))
                .andExpect(jsonPath("$.data.nextBatch.positionTo").value(nullValue()));
    }

    @Test
    void 오픈_전에는_차수를_알려_주지_않는다() throws Exception {
        Instant opensAt = Instant.now().plusSeconds(3600);
        PreorderProduct product = fixtures.preorderProduct(opensAt, opensAt.plusSeconds(3600));

        mockMvc.perform(get(PATH, product.productId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saleStatus").value("BEFORE_OPEN"))
                .andExpect(jsonPath("$.data.nextBatch").value(nullValue()));
    }

    @Test
    void 조기_마감한_뒤에는_마감이고_차수를_알려_주지_않는다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();
        mockMvc.perform(post("/api/v1/admin/preorders/products/{id}/campaign/close", product.productId())
                .with(admin())).andExpect(status().isOk());

        mockMvc.perform(get(PATH, product.productId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saleStatus").value("CLOSED"))
                .andExpect(jsonPath("$.data.nextBatch").value(nullValue()));
    }

    @Test
    void 회차가_없거나_공개_전인_상품은_404_다() throws Exception {
        PreorderProduct hidden = fixtures.openPreorderProduct();
        campaigns.applyVisibility(hidden.productId(), false, 1);

        mockMvc.perform(get(PATH, hidden.productId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
        mockMvc.perform(get(PATH, UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
    }
}
