package com.grandis.nova.preorder.campaign;

import com.grandis.nova.preorder.campaign.application.PreorderCampaignAdminService;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.support.CatalogStubs;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * catalog 가 회차 일정을 묻는 내부 API. 토큰 없이 부른다.
 * 공유 DB 에는 다른 시험이 남긴 회차가 섞이므로 접수 중 목록은 이 시험이 만든 상품이 들었는지 · 빠졌는지로 본다.
 */
@PreorderIntegrationTest
@AutoConfigureMockMvc
class InternalCampaignApiTest {

    static final String OPEN = "/internal/preorders/campaigns/open";
    static final String BY_PRODUCT = "/internal/preorders/campaigns";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    Campaigns campaigns;

    @Autowired
    PreorderCampaignAdminService campaignService;

    @MockitoBean
    CatalogClient catalogClient;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @Test
    void 접수_중이거나_10분_안에_열릴_회차만_시각과_함께_준다() throws Exception {
        Instant now = Instant.now();
        PreorderProduct open = fixtures.openPreorderProduct();
        PreorderProduct soon = fixtures.preorderProduct(now.plusSeconds(300), now.plusSeconds(3600));
        PreorderProduct later = fixtures.preorderProduct(now.plusSeconds(1800), now.plusSeconds(3600));
        PreorderProduct ended = fixtures.preorderProduct(now.minusSeconds(7200), now.minusSeconds(3600));

        String body = mockMvc.perform(get(OPEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.serverTime").isNotEmpty())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Map<String, Map<String, Object>> items = byProduct(JsonPath.read(body, "$.data.items"));
        assertThat(items).containsKeys(open.productId().toString(), soon.productId().toString())
                .doesNotContainKeys(later.productId().toString(), ended.productId().toString());
        assertThat(items.get(soon.productId().toString()).get("opensAt")).as("상품별 회차와 같은 시각")
                .isEqualTo(opensAt(soon.productId()).toString());
        List<String> opensAt = JsonPath.read(body, "$.data.items[*].opensAt");
        assertThat(opensAt).as("오픈이 이른 순").isSortedAccordingTo((a, b) -> Instant.parse(a).compareTo(Instant.parse(b)));
    }

    @Test
    void 상품별_회차는_있는_상품만_한_번씩_준다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();
        UUID missing = UUID.randomUUID();

        byIds(product.productId() + "," + missing + "," + product.productId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].productId").value(product.productId().toString()))
                .andExpect(jsonPath("$.data.items[0].opensAt").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].closesAt").isNotEmpty());
        byIds(missing.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    void 상품_id_가_없거나_비었거나_100개를_넘거나_UUID_가_아니면_400() throws Exception {
        String hundredOne = IntStream.rangeClosed(1, 101).mapToObj(i -> UUID.randomUUID().toString())
                .collect(Collectors.joining(","));
        String hundred = IntStream.rangeClosed(1, 100).mapToObj(i -> UUID.randomUUID().toString())
                .collect(Collectors.joining(","));

        mockMvc.perform(get(BY_PRODUCT)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("productIds"));
        for (String ids : List.of("", UUID.randomUUID() + ",", hundredOne, "not-a-uuid")) {
            byIds(ids).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.details.violations[0].field").value("productIds"));
        }
        byIds(hundred).andExpect(status().isOk());
    }

    @Test
    void 마감과_다시_열기는_마감_시각을_바꾸고_오픈_전_판매_중지는_오픈_시각도_당긴다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();
        CatalogStubs.stubPreorderProduct(catalogClient, product.productId(), CatalogStubs.activeOption(product.optionId()));
        Instant before = Instant.now();

        campaignService.closeCampaign(product.productId(), "admin");
        assertThat(closesAt(product.productId())).isBetween(before.truncatedTo(ChronoUnit.MICROS), Instant.now());
        Instant reopened = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MICROS);
        campaignService.reopenCampaign(product.productId(), reopened, "admin");
        assertThat(closesAt(product.productId())).isEqualTo(reopened);

        Instant opensAt = Instant.now().plusSeconds(3600);
        PreorderProduct upcoming = fixtures.preorderProduct(opensAt, opensAt.plusSeconds(3600));
        campaigns.closeNow(upcoming.productId(), Instant.now());
        String body = byIds(upcoming.productId().toString()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        Instant upcomingOpensAt = Instant.parse(JsonPath.read(body, "$.data.items[0].opensAt"));
        Instant upcomingClosesAt = Instant.parse(JsonPath.read(body, "$.data.items[0].closesAt"));
        assertThat(upcomingOpensAt).as("오픈 전 판매 중지는 오픈도 지난 시각").isBefore(upcomingClosesAt)
                .isBefore(Instant.now());
    }

    @Test
    void 회차_일정만_토큰_없이_열리고_다른_내부_API_는_401() throws Exception {
        mockMvc.perform(get(OPEN)).andExpect(status().isOk());
        byIds(UUID.randomUUID().toString()).andExpect(status().isOk());
        mockMvc.perform(get("/internal/preorders/{id}/payability", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions byIds(String productIds) throws Exception {
        return mockMvc.perform(get(BY_PRODUCT).param("productIds", productIds));
    }

    private Instant closesAt(UUID productId) throws Exception {
        String body = byIds(productId.toString()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return Instant.parse(JsonPath.read(body, "$.data.items[0].closesAt"));
    }

    private Instant opensAt(UUID productId) throws Exception {
        String body = byIds(productId.toString()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return Instant.parse(JsonPath.read(body, "$.data.items[0].opensAt"));
    }

    private static Map<String, Map<String, Object>> byProduct(List<Map<String, Object>> items) {
        return items.stream().collect(Collectors.toMap(item -> (String) item.get("productId"), item -> item));
    }
}
