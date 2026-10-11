package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.UuidBinary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@CatalogIntegrationTest
@AutoConfigureMockMvc
class AdminProductDetailApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration HOUR = Duration.ofHours(1);
    private static final String PATH = "/api/v1/admin/products";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    ShopFixtures fixtures;
    UUID categoryId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        categoryId = fixtures.category();
    }

    @Test
    @DisplayName("등록한 상품의 관리자 상세 — 회원 상세와 같은 상품 모양(visible 실제 값) + tags + 등록 상태(준비는 회차 행이 생겨야)")
    void registeredProductDetail() throws Exception {
        Instant opensAt = Instant.now().plus(HOUR.multipliedBy(2));
        String key = "k-" + ShopFixtures.unique();
        String body = """
                { "categoryId": "%s", "saleMode": "PREORDER", "title": "Nova 1", "description": "설명", "tags": "nova,신제품",
                  "visible": true, "basePrice": 1000000,
                  "optionAxes": [ { "key": "color", "label": "색상", "values": [ { "value": "블랙" } ] } ],
                  "images": { "gallery": [ { "color": "블랙", "items": [ { "url": "https://img/b0.jpg" } ] } ] },
                  "campaign": { "opensAt": "%s", "closesAt": "%s" },
                  "shipmentBatches": [ { "batchNumber": 1, "positionFrom": 1, "positionTo": null, "estimatedShipStart": "2026-11-01", "estimatedShipEnd": "2026-11-07" } ] }
                """.formatted(categoryId, opensAt, opensAt.plus(Duration.ofDays(3)));
        ResultActions created = mockMvc.perform(post(PATH).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(body).with(user("admin").roles("ADMIN"))).andExpect(status().isCreated());
        JsonNode createdData = data(created);
        UUID productId = UUID.fromString(createdData.get("registration").get("productId").asString());

        JsonNode detail = data(admin(productId).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store"))));
        assertThat(detail.get("product")).as("201 의 미리보기와 같은 모양").isEqualTo(createdData.get("product"));
        assertThat(detail.get("product").get("visible").asBoolean()).as("고른 공개 여부 그대로 — 회원 노출은 준비가 정한다").isTrue();
        assertThat(detail.get("product").get("variants")).hasSize(1);
        assertThat(detail.get("product").get("images").get("gallery").get(0).get("bundleKey").asString()).isEqualTo("블랙");
        assertThat(detail.get("tags").asString()).isEqualTo("nova,신제품");
        JsonNode registration = detail.get("registration");
        assertThat(registration.get("productId").asString()).isEqualTo(productId.toString());
        assertThat(registration.get("idempotencyKey").asString()).isEqualTo(key);
        assertThat(registration.get("completed").asBoolean()).as("회차 행 전").isFalse();
        assertThat(registration.size()).as("등록 상태는 productId · idempotencyKey · completed 셋뿐").isEqualTo(3);

        // preorder 가 등록 이벤트를 처리해 회차 행을 만들면 준비가 끝난다
        fixtures.campaign(productId, opensAt, opensAt.plus(Duration.ofDays(3)));
        assertThat(data(admin(productId).andExpect(status().isOk())).get("registration").get("completed").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("노출 규칙이 없다 — 등록 기록 없음(idempotencyKey null, 준비는 그대로 실림) · 비공개 · 판매 중지 · 오래된 마감도 200, 없는 상품만 404 NOT_FOUND")
    void noExposureRule() throws Exception {
        UUID noRegistration = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "등록 없음", "legacy");
        JsonNode legacy = data(admin(noRegistration).andExpect(status().isOk()));
        assertThat(legacy.get("registration").get("idempotencyKey").isNull()).as("등록 기록이 없다").isTrue();
        assertThat(legacy.get("registration").get("completed").asBoolean()).as("준비 전 — 관리자 목록과 같은 판정").isFalse();
        fixtures.option(noRegistration, "ACTIVE");
        fixtures.stockReady(noRegistration);
        assertThat(data(admin(noRegistration).andExpect(status().isOk())).get("registration").get("completed").asBoolean())
                .as("기록이 없어도 재고 행이 생기면 준비").isTrue();
        assertThat(legacy.get("tags").asString()).isEqualTo("legacy");
        assertThat(legacy.get("product").get("visible").asBoolean()).as("관리자 상세의 visible 은 칸 그대로(기본값 1). 회원 노출은 판매 방식별 준비도 필요하다").isTrue();

        UUID hidden = fixtures.product(categoryId, "IN_STOCK", "ACTIVE", "비공개", null);
        fixtures.registration(hidden);
        fixtures.option(hidden, "ACTIVE");
        fixtures.stockReady(hidden);
        jdbcTemplate.update("UPDATE products SET visible = 0, status = 'PAUSED' WHERE id = ?", (Object) UuidBinary.toBytes(hidden));
        JsonNode hiddenDetail = data(admin(hidden).andExpect(status().isOk()));
        assertThat(hiddenDetail.get("product").get("visible").asBoolean()).isFalse();
        assertThat(hiddenDetail.get("product").get("status").asString()).isEqualTo("PAUSED");
        assertThat(hiddenDetail.get("registration").get("completed").asBoolean()).isTrue();

        Instant now = Instant.now();
        UUID longClosed = fixtures.product(categoryId, "PREORDER", "ACTIVE", "오래 전 마감", null);
        fixtures.registration(longClosed);
        fixtures.campaign(longClosed, now.minus(HOUR.multipliedBy(200)), now.minus(HOUR.multipliedBy(190)));
        assertThat(data(admin(longClosed).andExpect(status().isOk())).get("product").get("campaign").get("status").asString())
                .isEqualTo("CLOSED");

        admin(UUID.randomUUID()).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("관리자만 — 익명 401, 회원 403")
    void adminOnly() throws Exception {
        UUID productId = fixtures.product(categoryId, "IN_STOCK", "ACTIVE");
        mockMvc.perform(get(PATH + "/{id}", productId)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH + "/{id}", productId).with(user("657").roles("USER"))).andExpect(status().isForbidden());
    }

    private ResultActions admin(UUID productId) throws Exception {
        return mockMvc.perform(get(PATH + "/{id}", productId).with(user("admin").roles("ADMIN")));
    }

    private static JsonNode data(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("data");
    }
}
