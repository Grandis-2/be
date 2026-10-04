package com.grandis.nova.preorder.campaign;

import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.support.CatalogStubs;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

import static com.grandis.nova.preorder.support.AccessTokens.admin;
import static com.grandis.nova.preorder.support.AccessTokens.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@PreorderIntegrationTest
@AutoConfigureMockMvc
class AdminProductPreorderApiTest {

    static final String THREE_BATCHES = """
            {"batches":[
              {"batchNumber":1,"positionFrom":1,"positionTo":1000,
               "estimatedShipStart":"2026-09-25","estimatedShipEnd":"2026-09-29"},
              {"batchNumber":2,"positionFrom":1001,"positionTo":5000,
               "estimatedShipStart":"2026-10-05","estimatedShipEnd":"2026-10-09"},
              {"batchNumber":3,"positionFrom":5001,"positionTo":null,
               "estimatedShipStart":"2026-10-19","estimatedShipEnd":"2026-11-06"}]}""";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @Test
    void 회차가_없으면_만들고_다시_부르면_바꾼다() throws Exception {
        Long productId = preorderProduct();
        Instant opensAt = Instant.now().plusSeconds(3600);

        putCampaign(productId, opensAt, opensAt.plusSeconds(3600))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(productId))
                .andExpect(jsonPath("$.data.saleStatus").value("BEFORE_OPEN"))
                .andExpect(jsonPath("$.data.issuedCount").value(0))
                .andExpect(jsonPath("$.data.openNotifiedAt").doesNotExist());

        Instant changed = opensAt.plusSeconds(7200);
        putCampaign(productId, changed, changed.plusSeconds(3600))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.opensAt").value(changed.toString()));
        assertThat(fixtures.count("SELECT COUNT(*) FROM preorder_campaigns WHERE product_id = ?", productId)).isEqualTo(1);
    }

    @Test
    void 오픈_뒤에는_일정도_차수도_바꿀_수_없다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();
        CatalogStubs.stubPreorderProduct(catalogClient, product.productId(), CatalogStubs.activeOption(product.optionId()));
        Instant later = Instant.now().plusSeconds(3600);
        LocalDateTime opensAtBefore = opensAtOf(product.productId());

        putCampaign(product.productId(), later, later.plusSeconds(3600))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_ALREADY_OPEN"));
        mockMvc.perform(put("/api/v1/admin/products/{id}/shipment-batches", product.productId())
                        .contentType(MediaType.APPLICATION_JSON).content(THREE_BATCHES)
                        .with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_ALREADY_OPEN"));
        assertThat(fixtures.count("SELECT COUNT(*) FROM shipment_batches WHERE product_id = ?",
                product.productId())).as("차수는 그대로다").isEqualTo(2);
        assertThat(opensAtOf(product.productId())).as("일정도 그대로다").isEqualTo(opensAtBefore);
    }

    @Test
    void 오픈_시각이_과거면_400() throws Exception {
        Long productId = preorderProduct();
        Instant past = Instant.now().minusSeconds(60);

        putCampaign(productId, past, past.plusSeconds(7200))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("opensAt"));
        assertThat(fixtures.count("SELECT COUNT(*) FROM preorder_campaigns WHERE product_id = ?", productId))
                .isZero();
    }

    @Test
    void 오픈까지_최소_준비_시간보다_가까우면_400() throws Exception {
        Long productId = preorderProduct();
        Instant tooSoon = Instant.now().plus(Duration.ofMinutes(9));

        putCampaign(productId, tooSoon, tooSoon.plusSeconds(7200))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("opensAt"));

        Instant enough = Instant.now().plus(Duration.ofMinutes(11));
        putCampaign(productId, enough, enough.plusSeconds(7200)).andExpect(status().isOk());
    }

    @Test
    void 차수_목록에_빈_항목이_있으면_400() throws Exception {
        Long productId = preorderProduct();
        Instant opensAt = Instant.now().plusSeconds(3600);
        putCampaign(productId, opensAt, opensAt.plusSeconds(3600)).andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/admin/products/{id}/shipment-batches", productId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"batches\":[null]}")
                        .with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 마감이_오픈보다_앞서면_400() throws Exception {
        Long productId = preorderProduct();
        Instant opensAt = Instant.now().plusSeconds(3600);

        putCampaign(productId, opensAt, opensAt)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("closesAt"));
    }

    @Test
    void 일반_상품과_없는_상품은_404() throws Exception {
        Long inStock = fixtures.product("IN_STOCK", "ACTIVE");
        CatalogStubs.stubProduct(catalogClient, inStock, "IN_STOCK", "ACTIVE", CatalogStubs.activeOption(1L));
        Long missing = fixtures.product("PREORDER", "ACTIVE");
        CatalogStubs.stubNotFound(catalogClient, missing);
        Instant opensAt = Instant.now().plusSeconds(3600);

        putCampaign(inStock, opensAt, opensAt.plusSeconds(60))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
        putCampaign(missing, opensAt, opensAt.plusSeconds(60))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/admin/products/{id}/preorder-campaign", inStock)
                        .with(admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 차수를_전체_교체하고_다시_조회한다() throws Exception {
        Long productId = preorderProduct();
        Instant opensAt = Instant.now().plusSeconds(3600);
        putCampaign(productId, opensAt, opensAt.plusSeconds(3600)).andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/admin/products/{id}/shipment-batches", productId)
                        .contentType(MediaType.APPLICATION_JSON).content(THREE_BATCHES)
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.items[2].positionTo").doesNotExist());

        mockMvc.perform(put("/api/v1/admin/products/{id}/shipment-batches", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batches":[{"batchNumber":1,"positionFrom":1,"positionTo":null,
                                 "estimatedShipStart":"2026-09-25","estimatedShipEnd":"2026-09-29"}]}""")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)));
        mockMvc.perform(get("/api/v1/admin/products/{id}/shipment-batches", productId)
                        .with(admin()))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].batchNumber").value(1));
        assertThat(fixtures.count("SELECT COUNT(*) FROM shipment_batches WHERE product_id = ?", productId)).isEqualTo(1);
    }

    @Test
    void 구간이_어긋나면_400_SHIPMENT_BATCH_INVALID() throws Exception {
        Long productId = preorderProduct();
        Instant opensAt = Instant.now().plusSeconds(3600);
        putCampaign(productId, opensAt, opensAt.plusSeconds(3600)).andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/admin/products/{id}/shipment-batches", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batches":[
                                  {"batchNumber":1,"positionFrom":1,"positionTo":1000,
                                   "estimatedShipStart":"2026-09-25","estimatedShipEnd":"2026-09-29"},
                                  {"batchNumber":2,"positionFrom":900,"positionTo":null,
                                   "estimatedShipStart":"2026-10-05","estimatedShipEnd":"2026-10-09"}]}""")
                        .with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SHIPMENT_BATCH_INVALID"))
                .andExpect(jsonPath("$.error.details.reason").exists());
        assertThat(fixtures.count("SELECT COUNT(*) FROM shipment_batches WHERE product_id = ?", productId)).isZero();
    }

    @Test
    void 회차가_없으면_차수를_설정할_수_없다() throws Exception {
        Long productId = preorderProduct();

        mockMvc.perform(put("/api/v1/admin/products/{id}/shipment-batches", productId)
                        .contentType(MediaType.APPLICATION_JSON).content(THREE_BATCHES)
                        .with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void 전체_재발행은_관리자만_202_와_건수() throws Exception {
        Instant opensAt = Instant.now().plusSeconds(3600);
        fixtures.preorderProduct(opensAt, opensAt.plusSeconds(3600));

        mockMvc.perform(post("/api/v1/admin/preorders/campaigns/republish").with(admin()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.published").value(greaterThanOrEqualTo(1)));
        mockMvc.perform(post("/api/v1/admin/preorders/campaigns/republish").with(customer(1024L)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 판매_중지_상품에도_일정을_잡을_수_있다() throws Exception {
        Long productId = fixtures.product("PREORDER", "PAUSED");
        CatalogStubs.stubProduct(catalogClient, productId, "PREORDER", "PAUSED", CatalogStubs.activeOption(1L));
        Instant opensAt = Instant.now().plusSeconds(3600);

        putCampaign(productId, opensAt, opensAt.plusSeconds(3600)).andExpect(status().isOk());
    }

    @Test
    void 공개_전이고_등록이_끝나지_않은_상품에도_일정을_잡을_수_있다() throws Exception {
        Long productId = fixtures.product("PREORDER", "ACTIVE");
        CatalogStubs.stubPreorderProduct(catalogClient, productId, false, false, CatalogStubs.activeOption(1L));
        Instant opensAt = Instant.now().plusSeconds(3600);

        putCampaign(productId, opensAt, opensAt.plusSeconds(3600)).andExpect(status().isOk());
    }

    @Test
    void 사용자_토큰은_403_로그인_없으면_401() throws Exception {
        Long productId = preorderProduct();

        mockMvc.perform(get("/api/v1/admin/products/{id}/preorder-campaign", productId)
                        .with(customer(1024L)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/products/{id}/preorder-campaign", productId))
                .andExpect(status().isUnauthorized());
    }

    private Long preorderProduct() {
        Long productId = fixtures.product("PREORDER", "ACTIVE");
        CatalogStubs.stubPreorderProduct(catalogClient, productId, CatalogStubs.activeOption(1L));
        return productId;
    }

    /** DB 는 UTC 벽시계 시각을 담는다. */
    private LocalDateTime opensAtOf(Long productId) {
        return jdbcTemplate.queryForObject("SELECT opens_at FROM preorder_campaigns WHERE product_id = ?",
                LocalDateTime.class, productId);
    }

    private ResultActions putCampaign(Long productId, Instant opensAt, Instant closesAt) throws Exception {
        return mockMvc.perform(put("/api/v1/admin/products/{id}/preorder-campaign", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"opensAt\":\"%s\",\"closesAt\":\"%s\"}".formatted(opensAt, closesAt))
                .with(admin()));
    }

}
