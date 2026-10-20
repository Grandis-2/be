package com.grandis.nova.preorder.campaign;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.preorder.PreorderErrorCode;
import com.grandis.nova.preorder.accept.application.AcceptResult;
import com.grandis.nova.preorder.accept.application.PreorderAcceptService;
import com.grandis.nova.preorder.campaign.application.PreorderCampaignAdminService;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.integration.catalog.CatalogReader;
import com.grandis.nova.preorder.support.AcceptFixtures;
import com.grandis.nova.preorder.support.CatalogStubs;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.ResourceAccessException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.grandis.nova.preorder.support.AccessTokens.admin;
import static com.grandis.nova.preorder.support.AccessTokens.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@PreorderIntegrationTest
@AutoConfigureMockMvc
class AdminProductPreorderApiTest {

    static final String CAMPAIGN_CHANGED = "PREORDER_CAMPAIGN_CHANGED";

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

    @Autowired
    PreorderAcceptService acceptService;

    @Autowired
    PreorderCampaignAdminService campaignService;

    @Autowired
    Campaigns campaigns;

    @MockitoBean
    CatalogClient catalogClient;

    /** 다시 연 뒤 재확인 사이에 끼어들려고 상품 읽기만 가로챈다(실제 동작은 그대로). */
    @MockitoSpyBean
    CatalogReader catalogReader;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @Test
    void 회차가_없으면_만들고_다시_부르면_바꾼다() throws Exception {
        UUID productId = preorderProduct();
        Instant opensAt = Instant.now().plusSeconds(3600);

        putCampaign(productId, opensAt, opensAt.plusSeconds(3600))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(productId.toString()))
                .andExpect(jsonPath("$.data.saleStatus").value("BEFORE_OPEN"))
                .andExpect(jsonPath("$.data.issuedCount").value(0))
                .andExpect(jsonPath("$.data.openNotifiedAt").doesNotExist());

        Instant changed = opensAt.plusSeconds(7200);
        putCampaign(productId, changed, changed.plusSeconds(3600))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.opensAt").value(changed.toString()));
        assertThat(fixtures.count("SELECT COUNT(*) FROM preorder_campaigns WHERE product_id = ?",
                (Object) UuidBinary.toBytes(productId))).isEqualTo(1);
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
        mockMvc.perform(put("/api/v1/admin/preorders/products/{id}/shipment-batches", product.productId())
                        .contentType(MediaType.APPLICATION_JSON).content(THREE_BATCHES)
                        .with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_ALREADY_OPEN"));
        assertThat(fixtures.count("SELECT COUNT(*) FROM shipment_batches WHERE product_id = ?",
                (Object) UuidBinary.toBytes(product.productId()))).as("차수는 그대로다").isEqualTo(2);
        assertThat(opensAtOf(product.productId())).as("일정도 그대로다").isEqualTo(opensAtBefore);
    }

    @Test
    void 오픈_시각이_과거면_400() throws Exception {
        UUID productId = preorderProduct();
        Instant past = Instant.now().minusSeconds(60);

        putCampaign(productId, past, past.plusSeconds(7200))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("opensAt"));
        assertThat(fixtures.count("SELECT COUNT(*) FROM preorder_campaigns WHERE product_id = ?",
                (Object) UuidBinary.toBytes(productId)))
                .isZero();
    }

    @Test
    void 오픈까지_최소_준비_시간보다_가까우면_400() throws Exception {
        UUID productId = preorderProduct();
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
        UUID productId = preorderProduct();
        Instant opensAt = Instant.now().plusSeconds(3600);
        putCampaign(productId, opensAt, opensAt.plusSeconds(3600)).andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/admin/preorders/products/{id}/shipment-batches", productId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"batches\":[null]}")
                        .with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 마감이_오픈보다_앞서면_400() throws Exception {
        UUID productId = preorderProduct();
        Instant opensAt = Instant.now().plusSeconds(3600);

        putCampaign(productId, opensAt, opensAt)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("closesAt"));
    }

    @Test
    void 일반_상품과_없는_상품은_404() throws Exception {
        UUID inStock = fixtures.product("IN_STOCK", "ACTIVE");
        CatalogStubs.stubProduct(catalogClient, inStock, "IN_STOCK", "ACTIVE",
                CatalogStubs.activeOption(UUID.randomUUID()));
        UUID missing = fixtures.product("PREORDER", "ACTIVE");
        CatalogStubs.stubNotFound(catalogClient, missing);
        Instant opensAt = Instant.now().plusSeconds(3600);

        putCampaign(inStock, opensAt, opensAt.plusSeconds(60))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
        putCampaign(missing, opensAt, opensAt.plusSeconds(60))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/admin/preorders/products/{id}/campaign", inStock)
                        .with(admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void 차수를_전체_교체하고_다시_조회한다() throws Exception {
        UUID productId = preorderProduct();
        Instant opensAt = Instant.now().plusSeconds(3600);
        putCampaign(productId, opensAt, opensAt.plusSeconds(3600)).andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/admin/preorders/products/{id}/shipment-batches", productId)
                        .contentType(MediaType.APPLICATION_JSON).content(THREE_BATCHES)
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(3)))
                .andExpect(jsonPath("$.data.items[2].positionTo").doesNotExist());

        mockMvc.perform(put("/api/v1/admin/preorders/products/{id}/shipment-batches", productId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batches":[{"batchNumber":1,"positionFrom":1,"positionTo":null,
                                 "estimatedShipStart":"2026-09-25","estimatedShipEnd":"2026-09-29"}]}""")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)));
        mockMvc.perform(get("/api/v1/admin/preorders/products/{id}/shipment-batches", productId)
                        .with(admin()))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].batchNumber").value(1));
        assertThat(fixtures.count("SELECT COUNT(*) FROM shipment_batches WHERE product_id = ?",
                (Object) UuidBinary.toBytes(productId))).isEqualTo(1);
    }

    @Test
    void 구간이_어긋나면_400_SHIPMENT_BATCH_INVALID() throws Exception {
        UUID productId = preorderProduct();
        Instant opensAt = Instant.now().plusSeconds(3600);
        putCampaign(productId, opensAt, opensAt.plusSeconds(3600)).andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/admin/preorders/products/{id}/shipment-batches", productId)
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
        assertThat(fixtures.count("SELECT COUNT(*) FROM shipment_batches WHERE product_id = ?",
                (Object) UuidBinary.toBytes(productId))).isZero();
    }

    @Test
    void 회차가_없으면_차수를_설정할_수_없다() throws Exception {
        UUID productId = preorderProduct();

        mockMvc.perform(put("/api/v1/admin/preorders/products/{id}/shipment-batches", productId)
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
        mockMvc.perform(post("/api/v1/admin/preorders/campaigns/republish").with(customer(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    void 판매_중지_상품에도_일정을_잡을_수_있다() throws Exception {
        UUID productId = fixtures.product("PREORDER", "PAUSED");
        CatalogStubs.stubProduct(catalogClient, productId, "PREORDER", "PAUSED",
                CatalogStubs.activeOption(UUID.randomUUID()));
        Instant opensAt = Instant.now().plusSeconds(3600);

        putCampaign(productId, opensAt, opensAt.plusSeconds(3600)).andExpect(status().isOk());
    }

    @Test
    void 공개_전이고_등록이_끝나지_않은_상품에도_일정을_잡을_수_있다() throws Exception {
        UUID productId = fixtures.product("PREORDER", "ACTIVE");
        CatalogStubs.stubPreorderProduct(catalogClient, productId, false, false,
                CatalogStubs.activeOption(UUID.randomUUID()));
        Instant opensAt = Instant.now().plusSeconds(3600);

        putCampaign(productId, opensAt, opensAt.plusSeconds(3600)).andExpect(status().isOk());
    }

    @Test
    void 접수_중인_회차를_조기_마감하면_새_접수만_막히고_기존_예약은_그대로다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();
        AcceptFixtures accepts = new AcceptFixtures(acceptService, fixtures, catalogClient);
        AcceptResult accepted = accepts.accept(fixtures.customer(), product);
        int eventsBefore = fixtures.outboxEvents(CAMPAIGN_CHANGED, product.productId());
        Instant before = Instant.now();

        closeCampaign(product.productId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saleStatus").value("CLOSED"))
                .andExpect(jsonPath("$.data.issuedCount").value(1));

        Instant closesAt = closesAtOf(product.productId());
        assertThat(closesAt).isBetween(before.minusMillis(1), Instant.now());
        assertThat(fixtures.outboxEvents(CAMPAIGN_CHANGED, product.productId())).as("대기열에 마감을 알린다")
                .isEqualTo(eventsBefore + 1);
        assertThatThrownBy(() -> accepts.accept(fixtures.customer(), product))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(PreorderErrorCode.SALE_CLOSED);
        mockMvc.perform(get("/api/v1/admin/preorders/" + AcceptFixtures.tokenOf(accepted)).with(admin()))
                .andExpect(jsonPath("$.data.status").value("PENDING_SYNC"));
    }

    @Test
    void 이미_마감한_회차를_다시_마감해도_일정이_그대로이고_이벤트를_더_내지_않는다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();
        closeCampaign(product.productId()).andExpect(status().isOk());
        Instant closesAt = closesAtOf(product.productId());
        int events = fixtures.outboxEvents(CAMPAIGN_CHANGED, product.productId());

        closeCampaign(product.productId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saleStatus").value("CLOSED"));

        assertThat(closesAtOf(product.productId())).isEqualTo(closesAt);
        assertThat(fixtures.outboxEvents(CAMPAIGN_CHANGED, product.productId())).isEqualTo(events);
    }

    @Test
    void 오픈_전_회차는_조기_마감할_수_없고_회차가_없으면_404_다() throws Exception {
        Instant opensAt = Instant.now().plusSeconds(3600);
        PreorderProduct notOpened = fixtures.preorderProduct(opensAt, opensAt.plusSeconds(3600));

        closeCampaign(notOpened.productId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SALE_NOT_OPEN"));
        mockMvc.perform(get("/api/v1/admin/preorders/products/{id}/campaign", notOpened.productId()).with(admin()))
                .andExpect(jsonPath("$.data.saleStatus").value("BEFORE_OPEN"))
                .andExpect(jsonPath("$.data.opensAt").value(startsWith(opensAt.toString().substring(0, 19))));
        closeCampaign(UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void 마감한_회차를_다시_열면_접수가_순번을_이어_받고_기존_예약은_그대로다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();
        AcceptFixtures accepts = new AcceptFixtures(acceptService, fixtures, catalogClient);
        AcceptResult first = accepts.accept(fixtures.customer(), product);
        closeCampaign(product.productId()).andExpect(status().isOk());
        Instant closesAt = Instant.now().plusSeconds(3600);

        reopenCampaign(product.productId(), closesAt)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saleStatus").value("OPEN"))
                .andExpect(jsonPath("$.data.issuedCount").value(1));

        AcceptResult second = accepts.accept(fixtures.customer(), product);
        assertThat(second.preorder().queuePosition()).isEqualTo(first.preorder().queuePosition() + 1);
        mockMvc.perform(get("/api/v1/admin/preorders/" + AcceptFixtures.tokenOf(first)).with(admin()))
                .andExpect(jsonPath("$.data.status").value("PENDING_SYNC"));
    }

    @Test
    void 오픈_전이거나_아직_접수_중이면_409_마감_시각이_지금_이전이면_400_회차가_없으면_404() throws Exception {
        Instant now = Instant.now();
        PreorderProduct notOpened = fixtures.preorderProduct(now.plusSeconds(3600), now.plusSeconds(7200));
        PreorderProduct open = fixtures.openPreorderProduct();
        PreorderProduct closed = fixtures.preorderProduct(now.minusSeconds(7200), now.minusSeconds(3600));
        for (PreorderProduct product : new PreorderProduct[]{notOpened, open, closed}) {
            CatalogStubs.stubPreorderProduct(catalogClient, product.productId(), CatalogStubs.activeOption(product.optionId()));
        }
        UUID missing = UUID.randomUUID();
        CatalogStubs.stubPreorderProduct(catalogClient, missing, CatalogStubs.activeOption(UUID.randomUUID()));

        reopenCampaign(notOpened.productId(), now.plusSeconds(10_800))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SALE_NOT_OPEN"));
        reopenCampaign(open.productId(), now.plusSeconds(10_800))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CAMPAIGN_NOT_REOPENABLE"))
                .andExpect(jsonPath("$.error.details.reason").value("ACCEPTING"));
        reopenCampaign(closed.productId(), now.minusSeconds(60))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("closesAt"));
        reopenCampaign(missing, now.plusSeconds(10_800))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
        assertThat(fixtures.outboxEvents(CAMPAIGN_CHANGED, closed.productId())).as("거절하면 이벤트가 없다").isZero();
    }

    /** 회차 취소(판매 중지)로 마감한 회차는 열지 않는다. 캐시에 판매 중으로 남아 있어도 catalog 의 지금 값으로 가른다. */
    @Test
    void 지금_판매_중인_사전예약_상품이_아니면_다시_열_수_없다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();
        AcceptFixtures accepts = new AcceptFixtures(acceptService, fixtures, catalogClient);
        accepts.accept(fixtures.customer(), product);
        closeCampaign(product.productId()).andExpect(status().isOk());
        CatalogStubs.stubProduct(catalogClient, product.productId(), "PREORDER", "PAUSED",
                CatalogStubs.activeOption(product.optionId()));

        reopenCampaign(product.productId(), Instant.now().plusSeconds(3600))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CAMPAIGN_NOT_REOPENABLE"))
                .andExpect(jsonPath("$.error.details.reason").value("NOT_ON_SALE"));
        CatalogStubs.stubProduct(catalogClient, product.productId(), "IN_STOCK", "ACTIVE",
                CatalogStubs.activeOption(product.optionId()));
        reopenCampaign(product.productId(), Instant.now().plusSeconds(3600))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details.reason").value("NOT_ON_SALE"));
        mockMvc.perform(get("/api/v1/admin/preorders/products/{id}/campaign", product.productId()).with(admin()))
                .andExpect(jsonPath("$.data.saleStatus").value("CLOSED"));
    }

    /** 확인한 뒤 잠그기 전에 판매 중지가 끝까지 처리됐다. 커밋 뒤 다시 읽어 판매 중지면 다시 마감한다. */
    @Test
    void 다시_연_뒤_판매_중지가_보이면_다시_마감하고_409() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();
        closeCampaign(product.productId()).andExpect(status().isOk());
        given(catalogClient.getProduct(product.productId())).willReturn(
                CatalogStubs.preorderProduct(product.productId(), CatalogStubs.activeOption(product.optionId())),
                CatalogStubs.product(product.productId(), "PREORDER", "PAUSED",
                        CatalogStubs.activeOption(product.optionId())));
        int events = fixtures.outboxEvents(CAMPAIGN_CHANGED, product.productId());

        reopenCampaign(product.productId(), Instant.now().plusSeconds(3600))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details.reason").value("NOT_ON_SALE"));

        mockMvc.perform(get("/api/v1/admin/preorders/products/{id}/campaign", product.productId()).with(admin()))
                .andExpect(jsonPath("$.data.saleStatus").value("CLOSED"));
        assertThat(fixtures.outboxEvents(CAMPAIGN_CHANGED, product.productId())).as("다시 열기 · 다시 마감")
                .isEqualTo(events + 2);
    }

    /** 오픈 전에 판매 중지로 닫힌 회차는 원래 오픈 시각이 남아 있지 않다. 지금 열면 그 시각을 건너뛴다. */
    @Test
    void 한_번도_열린_적_없는_회차는_다시_열_수_없다() throws Exception {
        Instant opensAt = Instant.now().plusSeconds(3600);
        PreorderProduct product = fixtures.preorderProduct(opensAt, opensAt.plusSeconds(3600));
        CatalogStubs.stubPreorderProduct(catalogClient, product.productId(), CatalogStubs.activeOption(product.optionId()));
        campaigns.closeNow(product.productId(), Instant.now());

        reopenCampaign(product.productId(), Instant.now().plusSeconds(7200))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CAMPAIGN_NOT_REOPENABLE"))
                .andExpect(jsonPath("$.error.details.reason").value("NEVER_OPENED"));
    }

    /** 재확인을 기다리는 동안 다른 관리자가 마감하고 다시 열었다. 앞선 요청의 보정은 그 뒤의 다시 열기를 닫지 않는다. */
    @Test
    void 다시_연_뒤_보정은_그_사이의_다른_다시_열기를_닫지_않는다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();
        closeCampaign(product.productId()).andExpect(status().isOk());
        Instant closesAt = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
        CatalogStubs.stubPreorderProduct(catalogClient, product.productId(), CatalogStubs.activeOption(product.optionId()));
        AtomicInteger calls = new AtomicInteger();
        // 두 번째 읽기(앞선 요청의 재확인) 직전에 다른 관리자가 끼어들고, 앞선 요청에는 판매 중지로 보인다
        willAnswer(invocation -> {
            if (calls.incrementAndGet() != 2) {
                return invocation.callRealMethod();
            }
            campaignService.closeCampaign(product.productId(), "other-admin");
            campaignService.reopenCampaign(product.productId(), closesAt.plusSeconds(600), "other-admin");
            return Optional.of(CatalogStubs.product(product.productId(), "PREORDER", "PAUSED",
                    CatalogStubs.activeOption(product.optionId())).data());
        }).given(catalogReader).findProduct(product.productId());

        reopenCampaign(product.productId(), closesAt)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details.reason").value("NOT_ON_SALE"));

        mockMvc.perform(get("/api/v1/admin/preorders/products/{id}/campaign", product.productId()).with(admin()))
                .andExpect(jsonPath("$.data.saleStatus").value("OPEN"));
        assertThat(closesAtOf(product.productId())).isEqualTo(closesAt.plusSeconds(600));
    }

    /** 재확인을 하지 못하면 판매 중임을 확인하지 못한 회차라 되돌리고 실패로 답한다. 관리자는 다시 시도한다. */
    @Test
    void 다시_연_뒤_재확인을_못_하면_되돌리고_503() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();
        closeCampaign(product.productId()).andExpect(status().isOk());
        int events = fixtures.outboxEvents(CAMPAIGN_CHANGED, product.productId());
        given(catalogClient.getProduct(product.productId()))
                .willReturn(CatalogStubs.preorderProduct(product.productId(), CatalogStubs.activeOption(product.optionId())))
                .willThrow(new ResourceAccessException("catalog timeout"));

        reopenCampaign(product.productId(), Instant.now().plusSeconds(3600))
                .andExpect(status().isServiceUnavailable());

        mockMvc.perform(get("/api/v1/admin/preorders/products/{id}/campaign", product.productId()).with(admin()))
                .andExpect(jsonPath("$.data.saleStatus").value("CLOSED"));
        assertThat(fixtures.outboxEvents(CAMPAIGN_CHANGED, product.productId())).as("다시 열기 · 되돌리기")
                .isEqualTo(events + 2);
    }

    /** 공개 여부 변경도 일정 번호를 올린다. 그 사이 공개 여부가 바뀌어도 판매 중지를 본 보정은 회차를 닫는다. */
    @Test
    void 다시_연_뒤_공개_여부가_바뀌어도_판매_중지면_다시_마감한다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();
        closeCampaign(product.productId()).andExpect(status().isOk());
        CatalogStubs.stubPreorderProduct(catalogClient, product.productId(), CatalogStubs.activeOption(product.optionId()));
        AtomicInteger calls = new AtomicInteger();
        willAnswer(invocation -> {
            if (calls.incrementAndGet() != 2) {
                return invocation.callRealMethod();
            }
            campaigns.applyListing(product.productId(), new ProductListing("Nova 1", ProductStatus.PAUSED, false, 1));
            return Optional.of(CatalogStubs.product(product.productId(), "PREORDER", "PAUSED",
                    CatalogStubs.activeOption(product.optionId())).data());
        }).given(catalogReader).findProduct(product.productId());

        reopenCampaign(product.productId(), Instant.now().plusSeconds(3600))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.details.reason").value("NOT_ON_SALE"));

        mockMvc.perform(get("/api/v1/admin/preorders/products/{id}/campaign", product.productId()).with(admin()))
                .andExpect(jsonPath("$.data.saleStatus").value("CLOSED"));
    }

    @Test
    void 다시_열기는_관리자만_한다() throws Exception {
        PreorderProduct product = fixtures.preorderProduct(Instant.now().minusSeconds(7200),
                Instant.now().minusSeconds(3600));
        String path = "/api/v1/admin/preorders/products/{id}/campaign/reopen";
        String body = "{\"closesAt\":\"%s\"}".formatted(Instant.now().plusSeconds(3600));

        mockMvc.perform(post(path, product.productId()).with(customer(UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(path, product.productId()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(path, product.productId()).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 조기_마감은_관리자만_한다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();
        String path = "/api/v1/admin/preorders/products/{id}/campaign/close";

        mockMvc.perform(post(path, product.productId()).with(customer(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(path, product.productId())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/preorders/products/{id}/campaign", product.productId()).with(admin()))
                .andExpect(jsonPath("$.data.saleStatus").value("OPEN"));
    }

    @Test
    void 사용자_토큰은_403_로그인_없으면_401() throws Exception {
        UUID productId = preorderProduct();

        mockMvc.perform(get("/api/v1/admin/preorders/products/{id}/campaign", productId)
                        .with(customer(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/preorders/products/{id}/campaign", productId))
                .andExpect(status().isUnauthorized());
    }

    private UUID preorderProduct() {
        UUID productId = fixtures.product("PREORDER", "ACTIVE");
        CatalogStubs.stubPreorderProduct(catalogClient, productId, CatalogStubs.activeOption(UUID.randomUUID()));
        return productId;
    }

    /** DB 는 UTC 벽시계 시각을 담는다. */
    private LocalDateTime opensAtOf(UUID productId) {
        return jdbcTemplate.queryForObject("SELECT opens_at FROM preorder_campaigns WHERE product_id = ?",
                LocalDateTime.class, (Object) UuidBinary.toBytes(productId));
    }

    private ResultActions closeCampaign(UUID productId) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/preorders/products/{id}/campaign/close", productId).with(admin()));
    }

    private ResultActions reopenCampaign(UUID productId, Instant closesAt) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/preorders/products/{id}/campaign/reopen", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"closesAt\":\"%s\"}".formatted(closesAt))
                .with(admin()));
    }

    /** 응답으로 본 지금의 마감 시각. */
    private Instant closesAtOf(UUID productId) throws Exception {
        String body = mockMvc.perform(get("/api/v1/admin/preorders/products/{id}/campaign", productId).with(admin()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return Instant.parse(JsonPath.<String>read(body, "$.data.closesAt"));
    }

    private ResultActions putCampaign(UUID productId, Instant opensAt, Instant closesAt) throws Exception {
        return mockMvc.perform(put("/api/v1/admin/preorders/products/{id}/campaign", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"opensAt\":\"%s\",\"closesAt\":\"%s\"}".formatted(opensAt, closesAt))
                .with(admin()));
    }

}
