package com.grandis.nova.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 관리자 수정 API — 등록 API 로 만든 상품(축 color · storage, 조합 4개, 하나는 수동 가격)을 고친다.
 * 등록 직후 상품은 비공개 · 미완료라 회원 상세에는 안 나오지만 관리자 상세로 결과를 본다.
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
class AdminProductEditApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PATH = "/api/v1/admin/products";
    private static final Duration HOUR = Duration.ofHours(1);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    ShopFixtures fixtures;
    Long categoryId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        categoryId = fixtures.category();
    }

    @Nested
    @DisplayName("상품 정보 · 가격 · 보증")
    class ProductEdit {

        @Test
        @DisplayName("보낸 칸만 바뀐다 — 제목만 보내면 설명 · 태그는 그대로. 빈 본문 · 모르는 칸 · 긴 제목은 400")
        void partialUpdate() throws Exception {
            long productId = registerInStock();

            JsonNode edited = data(edit(productId, """
                    { "title": "Nova 1 Pro" }
                    """).andExpect(status().isOk()));
            assertThat(edited.get("product").get("title").asString()).isEqualTo("Nova 1 Pro");
            assertThat(edited.get("product").get("description").asString()).isEqualTo("설명");
            assertThat(edited.get("tags").asString()).isEqualTo("nova");

            expectValidation(edit(productId, "{}"), "body");
            expectValidation(edit(productId, "{ \"titel\": \"x\" }"), "titel");
            expectValidation(edit(productId, "{ \"title\": \"" + "가".repeat(101) + "\" }"), "title");
            expectValidation(edit(productId, "{ \"warranty\": { \"offered\": false, \"surcharge\": 1000 } }"), "warranty.surcharge");
            edit(999_999_999L, "{ \"title\": \"x\" }").andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("기본 가격이 바뀌면 수동 가격이 아닌 옵션만 기본가 + 추가금으로 재계산된다. 보증도 함께 고칠 수 있다")
        void basePriceChangeRecomputesNonOverriddenOptions() throws Exception {
            long productId = registerInStock();
            assertThat(prices(productId)).containsEntry("블랙 / 256GB", "1000000").containsEntry("블랙 / 512GB", "1270000")
                    .containsEntry("화이트 / 256GB", "1000000").containsEntry("화이트 / 512GB", "1200000");

            JsonNode edited = data(edit(productId, """
                    { "basePrice": 1100000, "warranty": { "offered": true, "surcharge": 90000 } }
                    """).andExpect(status().isOk()));
            assertThat(edited.get("product").get("basePrice").decimalValue()).isEqualByComparingTo("1100000");
            assertThat(edited.get("product").get("warranty").get("surcharge").decimalValue()).isEqualByComparingTo("90000");
            assertThat(prices(productId)).as("수동 가격(블랙 / 512GB)은 그대로, 나머지는 재계산")
                    .containsEntry("블랙 / 256GB", "1100000").containsEntry("블랙 / 512GB", "1270000")
                    .containsEntry("화이트 / 256GB", "1100000").containsEntry("화이트 / 512GB", "1300000");

            JsonNode offeredOnly = data(edit(productId, "{ \"warranty\": { \"offered\": true } }").andExpect(status().isOk()));
            assertThat(offeredOnly.get("product").get("warranty").get("surcharge").decimalValue()).as("보낸 칸만 바뀐다 — 추가금은 그대로")
                    .isEqualByComparingTo("90000");
            JsonNode withdrawn = data(edit(productId, "{ \"warranty\": { \"offered\": false } }").andExpect(status().isOk()));
            assertThat(withdrawn.get("product").get("warranty").get("surcharge").decimalValue()).as("제공하지 않으면 0").isZero();
        }

        @Test
        @DisplayName("축 없는 상품은 옵션 표시명이 상품 제목이다 — 제목을 고치면 옵션 표시명도 따라간다(preorder 가 읽는 내부 옵션 조회도 같은 값)")
        void standaloneOptionFollowsProductTitle() throws Exception {
            long productId = registerRaw("""
                    { "categoryId": %d, "saleMode": "IN_STOCK", "title": "Solo Case", "visible": false, "basePrice": 9000,
                      "combinations": [ { "selections": {}, "stock": 7 } ] }
                    """.formatted(categoryId));
            edit(productId, "{ \"title\": \"Solo Case Pro\" }").andExpect(status().isOk());
            assertThat(titles(data(adminDetail(productId)))).containsExactly("Solo Case Pro");
            assertThat(jdbcTemplate.queryForObject("SELECT title FROM product_options WHERE product_id = ?", String.class, productId))
                    .isEqualTo("Solo Case Pro");
        }

        @Test
        @DisplayName("입력이 틀리면 그 칸의 400 이다(500 이 아니다) — 소수 금액 · 공백뿐인 제목 · 모르는 enum · 숫자 자리의 문자열")
        void malformedInputIsFieldError() throws Exception {
            long productId = registerInStock();
            expectValidation(edit(productId, "{ \"basePrice\": 1000.5 }"), "basePrice");
            expectValidation(edit(productId, "{ \"warranty\": { \"offered\": true, \"surcharge\": 0.5 } }"), "warranty.surcharge");
            expectValidation(edit(productId, "{ \"title\": \"   \" }"), "title");
            expectValidation(edit(productId, "{ \"basePrice\": \"abc\" }"), "basePrice");
            edit(productId, "{ \"basePrice\": 1000000.00 }").andExpect(status().isOk());   // 대조군 — 끝자리 0 은 정수 원

            long variantId = variantIdOf(productId, "블랙 / 256GB");
            expectValidation(editVariant(productId, variantId, "{ \"price\": 0.5 }"), "price");
            expectValidation(editVariant(productId, variantId, "{ \"status\": \"NOPE\" }"), "status");
            long storage512 = valueIdOf(productId, "storage", "512GB");
            expectValidation(mockMvc.perform(admin(patch(PATH + "/{id}/option-values/{v}", productId, storage512)).content("{ \"surcharge\": 0.5 }")), "surcharge");
            expectValidation(mockMvc.perform(admin(patch(PATH + "/{id}/option-values/{v}", productId, storage512)).content("{ \"value\": \"   \" }")), "value");
            expectValidation(mockMvc.perform(admin(post(PATH + "/{id}/option-values", productId)).content("{ \"axisKey\": \"color\", \"value\": \"레드\", \"surcharge\": 0.5 }")), "surcharge");
            expectValidation(mockMvc.perform(admin(post(PATH + "/{id}/option-values", productId)).content("{ \"axisKey\": \"color\", \"value\": \"\u3000\" }")), "value");
            expectValidation(mockMvc.perform(admin(post(PATH + "/{id}/variants", productId)).content("{ \"selections\": { \"color\": \"  \", \"storage\": \"256GB\" } }")), "selections.color");
            expectValidation(mockMvc.perform(admin(post(PATH + "/{id}/variants", productId)).content("{ \"selections\": { \"color\": \"블랙\", \"storage\": \"256GB\" }, \"price\": 0.5 }")), "price");
            assertThat(data(adminDetail(productId)).get("product").get("title").asString()).isEqualTo("Nova 1");
        }

        /**
         * 금액 칸은 decimal(12,0) — 넘으면 저장에서 500 이던 것을 원인 칸의 400 으로. 기본가 · 추가금을 바꿔 재계산한 옵션 가격도 같다
         * (수동 가격 옵션은 재계산하지 않으므로 검사에서 빠진다).
         */
        @Test
        @DisplayName("금액 상한(999,999,999,999)을 넘는 수정은 원인 칸의 400 이고 아무것도 바뀌지 않는다 — 기본가 · 추가금 재계산 · 옵션 가격 · 가격 되돌리기 · 옵션 추가")
        void amountsAboveColumnLimitAreFieldErrors() throws Exception {
            long productId = registerInStock();
            String max = "999999999999";
            expectValidation(edit(productId, "{ \"basePrice\": 1000000000000 }"), "basePrice");
            // 기본가 최댓값이면 화이트/512GB(수동 가격 아님)가 최댓값 + 200,000 이 된다
            expectValidation(edit(productId, "{ \"basePrice\": " + max + " }"), "basePrice");
            long storage512 = valueIdOf(productId, "storage", "512GB");
            expectValidation(mockMvc.perform(admin(patch(PATH + "/{id}/option-values/{v}", productId, storage512)).content("{ \"surcharge\": " + max + " }")), "surcharge");
            long white256 = variantIdOf(productId, "화이트 / 256GB");
            expectValidation(editVariant(productId, white256, "{ \"price\": 1000000000000 }"), "price");
            editVariant(productId, white256, "{ \"price\": " + max + " }").andExpect(status().isOk());   // 대조군 — 경계는 들어간다
            assertThat(prices(productId)).containsEntry("블랙 / 256GB", "1000000").containsEntry("화이트 / 512GB", "1200000")
                    .containsEntry("블랙 / 512GB", "1270000");

            // 512GB 를 고른 두 옵션을 모두 수동 가격으로 두고 기본가를 올리면 재계산은 256GB 들만 — 그 뒤 수동 가격을 풀면 최댓값 + 200,000 이다
            editVariant(productId, variantIdOf(productId, "화이트 / 512GB"), "{ \"price\": 1 }").andExpect(status().isOk());
            edit(productId, "{ \"basePrice\": " + max + " }").andExpect(status().isOk());
            expectValidation(editVariant(productId, variantIdOf(productId, "블랙 / 512GB"), "{ \"resetPrice\": true }"), "resetPrice");
            assertThat(prices(productId)).containsEntry("블랙 / 512GB", "1270000");

            // 큰 추가금 값을 더한 뒤 그 값으로 옵션을 만들면 계산 가격이 넘는다
            mockMvc.perform(admin(post(PATH + "/{id}/option-values", productId)).content("{ \"axisKey\": \"storage\", \"value\": \"1TB\", \"surcharge\": 1 }"))
                    .andExpect(status().isCreated());
            expectValidation(mockMvc.perform(admin(post(PATH + "/{id}/variants", productId))
                    .content("{ \"selections\": { \"color\": \"블랙\", \"storage\": \"1TB\" } }")), "selections");
            // 수동 가격을 주면 계산 가격은 저장하지 않는다 — 계산 가격이 넘어도 들어간다
            mockMvc.perform(admin(post(PATH + "/{id}/variants", productId))
                    .content("{ \"selections\": { \"color\": \"블랙\", \"storage\": \"1TB\" }, \"price\": 5 }")).andExpect(status().isCreated());
            assertThat(prices(productId)).containsEntry("블랙 / 1TB", "5");
            expectValidation(mockMvc.perform(admin(post(PATH + "/{id}/option-values", productId))
                    .content("{ \"axisKey\": \"color\", \"value\": \"레드\", \"surcharge\": 1000000000000 }")), "surcharge");
        }

        @Test
        @DisplayName("정수 원 금액은 받은 표기와 상관없이 소수점 없는 숫자로 저장 · 응답한다 — 1e3 · 2000.0 · 1.5e3 · 7.00e3 은 1000 · 2000 · 1500 · 7000")
        void wholeWonIsNormalizedToPlainInteger() throws Exception {
            long productId = registerInStock();
            JsonNode product = data(edit(productId, "{ \"basePrice\": 1e3, \"warranty\": { \"offered\": true, \"surcharge\": 2000.0 } }")
                    .andExpect(status().isOk())).get("product");
            assertThat(product.get("basePrice").toString()).isEqualTo("1000");
            assertThat(product.get("warranty").get("surcharge").toString()).isEqualTo("2000");

            JsonNode added = data(mockMvc.perform(admin(post(PATH + "/{id}/option-values", productId))
                    .content("{ \"axisKey\": \"color\", \"value\": \"레드\", \"surcharge\": 1.5e3 }")).andExpect(status().isCreated()));
            assertThat(added.get("product").get("optionAxes").get(0).get("values").get(2).get("surcharge").toString()).isEqualTo("1500");

            JsonNode variant = data(editVariant(productId, variantIdOf(productId, "화이트 / 256GB"), "{ \"price\": 7.00e3 }").andExpect(status().isOk()));
            assertThat(variant.get("price").toString()).isEqualTo("7000");
        }

        @Test
        @DisplayName("사전예약은 오픈 전에는 고칠 수 있고 오픈 뒤(회차 opens_at ≤ 지금)에는 409 STATE_CONFLICT — 회차가 없으면 오픈 전이다")
        void preorderIsFrozenAfterOpen() throws Exception {
            long productId = registerPreorder();
            edit(productId, "{ \"title\": \"회차 없음\" }").andExpect(status().isOk());
            Instant now = Instant.now();
            fixtures.campaign(productId, now.plus(HOUR), now.plus(HOUR.multipliedBy(2)));
            edit(productId, "{ \"title\": \"오픈 전\" }").andExpect(status().isOk());

            long opened = registerPreorder();
            fixtures.campaign(opened, now.minus(HOUR), now.plus(HOUR));
            for (String body : new String[] {"{ \"title\": \"오픈 뒤\" }", "{ \"basePrice\": 1 }", "{ \"description\": \"x\" }"}) {
                edit(opened, body).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));
            }
            assertThat(data(adminDetail(opened)).get("product").get("title").asString()).isEqualTo("Nova 1");
            assertThat(data(adminDetail(productId)).get("product").get("title").asString()).isEqualTo("오픈 전");
        }
    }

    @Nested
    @DisplayName("옵션 값 · 옵션(조합)")
    class OptionEdit {

        @Test
        @DisplayName("값 추가는 옵션을 만들지 않고, 그 값으로 조합을 추가하면 바로 판매 중 · 기본 SKU · 계산 가격이다. 중복 · 없는 축 · 용량 형식 · 없는 값 · 축 누락 · 같은 조합은 400")
        void addValueThenVariant() throws Exception {
            long productId = registerInStock();
            JsonNode afterValue = data(mockMvc.perform(admin(post(PATH + "/{id}/option-values", productId))
                            .content("{ \"axisKey\": \"Color\", \"value\": \"레드\" }"))
                    .andExpect(status().isCreated()));
            assertThat(afterValue.get("product").get("optionAxes").get(0).get("values")).hasSize(3);
            assertThat(afterValue.get("product").get("variants")).as("옵션은 안 생긴다").hasSize(4);

            mockMvc.perform(admin(post(PATH + "/{id}/option-values", productId)).content("{ \"axisKey\": \"color\", \"value\": \"Rosé\" }"))
                    .andExpect(status().isCreated());
            // 콜레이션이 같다고 보는 값(악센트 차이)은 같은 축에 둘 수 없다 — 등록과 같은 규칙
            expectValidation(mockMvc.perform(admin(post(PATH + "/{id}/option-values", productId)).content("{ \"axisKey\": \"color\", \"value\": \"Rose\" }")), "value");
            expectValidation(mockMvc.perform(admin(post(PATH + "/{id}/option-values", productId)).content("{ \"axisKey\": \"size\", \"value\": \"L\" }")), "axisKey");
            expectValidation(mockMvc.perform(admin(post(PATH + "/{id}/option-values", productId)).content("{ \"axisKey\": \"storage\", \"value\": \"big\" }")), "value");
            // 앱의 콜레이션 흉내가 못 잡는 같은 값(ß = ss)은 DB UNIQUE(uq_option_value)가 잡고 같은 400 이 된다
            mockMvc.perform(admin(post(PATH + "/{id}/option-values", productId)).content("{ \"axisKey\": \"color\", \"value\": \"Strasse\" }"))
                    .andExpect(status().isCreated());
            expectValidation(mockMvc.perform(admin(post(PATH + "/{id}/option-values", productId)).content("{ \"axisKey\": \"color\", \"value\": \"Straße\" }")), "value");

            JsonNode variant = data(mockMvc.perform(admin(post(PATH + "/{id}/variants", productId))
                            .content("{ \"selections\": { \"Color\": \"레드\", \"storage\": \"512 gb\" } }"))
                    .andExpect(status().isCreated()));
            assertThat(variant.get("title").asString()).isEqualTo("레드 / 512GB");
            assertThat(variant.get("sku").asString()).isEqualTo("레드-512GB");
            assertThat(variant.get("status").asString()).isEqualTo("ACTIVE");
            assertThat(variant.get("price").decimalValue()).as("기본가 + 512GB 추가금").isEqualByComparingTo("1200000");
            assertThat(variant.get("availableQuantity").asInt()).as("재고 행이 없으니 0 — 재고는 order 의 재고 API 로").isZero();
            assertThat(variant.get("selections").get("color").asString()).isEqualTo("레드");

            expectValidation(mockMvc.perform(admin(post(PATH + "/{id}/variants", productId)).content("{ \"selections\": { \"color\": \"레드\", \"storage\": \"512GB\" } }")), "selections");
            expectValidation(mockMvc.perform(admin(post(PATH + "/{id}/variants", productId)).content("{ \"selections\": { \"color\": \"블루\", \"storage\": \"512GB\" } }")), "selections.color");
            expectValidation(mockMvc.perform(admin(post(PATH + "/{id}/variants", productId)).content("{ \"selections\": { \"color\": \"레드\" } }")), "selections");
            expectValidation(mockMvc.perform(admin(post(PATH + "/{id}/variants", productId)).content("{ \"selections\": { \"color\": \"레드\", \"storage\": \"256GB\", \"size\": \"L\" } }")), "selections");
            expectValidation(mockMvc.perform(admin(post(PATH + "/{id}/variants", productId)).content("{ \"selections\": { \"color\": \"레드\", \"storage\": \"256GB\" }, \"sku\": \"레드-512GB\" }")), "sku");

            JsonNode manual = data(mockMvc.perform(admin(post(PATH + "/{id}/variants", productId))
                            .content("{ \"selections\": { \"color\": \"레드\", \"storage\": \"256GB\" }, \"price\": 999000, \"sku\": \"RED-256\" }"))
                    .andExpect(status().isCreated()));
            assertThat(manual.get("price").decimalValue()).isEqualByComparingTo("999000");
            edit(productId, "{ \"basePrice\": 1050000 }").andExpect(status().isOk());
            assertThat(prices(productId)).as("수동 가격은 재계산에서 빠지고 계산 가격은 따라간다")
                    .containsEntry("레드 / 256GB", "999000").containsEntry("레드 / 512GB", "1250000");

            expectValidation(mockMvc.perform(admin(post(PATH + "/{id}/variants", productId))
                    .content("{ \"selections\": { \"Color\": \"화이트\", \"color\": \"퍼플\", \"storage\": \"256GB\" } }")), "selections");
        }

        @Test
        @DisplayName("값에서 만들어지는 칸이 상한을 넘으면 400 — 기본 SKU 80자, 값 문구를 고쳐 길어진 옵션 표시명 120자")
        void derivedLengthsAreBounded() throws Exception {
            String finish = "가".repeat(60);
            String size = "나".repeat(49);   // 60 + " / " + 49 + " / " + "256GB" = 120 — 상한 그대로
            long productId = registerRaw("""
                    { "categoryId": %d, "saleMode": "IN_STOCK", "title": "Long", "visible": false, "basePrice": 1000,
                      "optionAxes": [
                        { "key": "finish", "label": "마감", "values": [ { "value": "%s" } ] },
                        { "key": "size", "label": "크기", "values": [ { "value": "%s" } ] },
                        { "key": "storage", "label": "용량", "values": [ { "value": "256GB" }, { "value": "512GB" } ] } ],
                      "combinations": [
                        { "selections": { "finish": "%s", "size": "%s", "storage": "256GB" }, "sku": "L1", "stock": 1 },
                        { "selections": { "finish": "%s", "size": "%s", "storage": "512GB" }, "excluded": true } ] }
                    """.formatted(categoryId, finish, size, finish, size, finish, size));
            String selections = "{ \"finish\": \"%s\", \"size\": \"%s\", \"storage\": \"512GB\" }".formatted(finish, size);

            expectValidation(mockMvc.perform(admin(post(PATH + "/{id}/variants", productId)).content("{ \"selections\": " + selections + " }")), "sku");
            mockMvc.perform(admin(post(PATH + "/{id}/variants", productId)).content("{ \"selections\": " + selections + ", \"sku\": \"L2\" }"))
                    .andExpect(status().isCreated());   // 대조군 — 같은 조합에 짧은 SKU

            long storage256 = valueIdOf(productId, "storage", "256GB");
            expectValidation(mockMvc.perform(admin(patch(PATH + "/{id}/option-values/{v}", productId, storage256)).content("{ \"value\": \"256 GB\" }")), "value");
            assertThat(titles(data(adminDetail(productId)))).as("거절된 문구 수정은 표시명을 남기지 않는다")
                    .contains(finish + " / " + size + " / 256GB");
        }

        @Test
        @DisplayName("정규화(NFC)로 늘어난 글자도 칼럼을 넘으면 그 칸의 400(500 이 아니다) — 값 추가 · 값 이름 60자, 축 없는 상품의 제목이 만드는 옵션 표시명 120자")
        void normalizedTextMustFitColumns() throws Exception {
            String expands = "\u0958".repeat(60);   // 받은 글자는 60자지만 NFC 는 한 글자를 두 글자(U+0915 U+093C)로 푼다 — 120자
            long productId = registerInStock();
            expectValidation(mockMvc.perform(admin(post(PATH + "/{id}/option-values", productId))
                    .content("{ \"axisKey\": \"color\", \"value\": \"%s\" }".formatted(expands))), "value");
            mockMvc.perform(admin(post(PATH + "/{id}/option-values", productId))
                    .content("{ \"axisKey\": \"color\", \"value\": \"%s\" }".formatted("\u0958".repeat(30))))
                    .andExpect(status().isCreated());   // 대조군 — 정규화해서 60자는 들어간다
            long black = valueIdOf(productId, "color", "블랙");
            expectValidation(mockMvc.perform(admin(patch(PATH + "/{id}/option-values/{v}", productId, black))
                    .content("{ \"value\": \"%s\" }".formatted(expands))), "value");
            // 용량은 비교 키를 대문자로 접어 표시값보다 길어질 수 있다(ß → SS) — 그런 값은 형식 검사가 400 으로 막는다
            expectValidation(mockMvc.perform(admin(post(PATH + "/{id}/option-values", productId))
                    .content("{ \"axisKey\": \"storage\", \"value\": \"%s\" }".formatted("ß".repeat(60)))), "value");

            long solo = registerRaw("""
                    { "categoryId": %d, "saleMode": "IN_STOCK", "title": "Solo", "visible": false, "basePrice": 9000,
                      "combinations": [ { "selections": {}, "stock": 1 } ] }
                    """.formatted(categoryId));
            expectValidation(edit(solo, "{ \"title\": \"%s\" }".formatted("\u0958".repeat(100))), "title");
            assertThat(jdbcTemplate.queryForObject("SELECT title FROM products WHERE id = ?", String.class, solo)).isEqualTo("Solo");
            edit(solo, "{ \"title\": \"%s\" }".formatted(expands)).andExpect(status().isOk());   // 대조군 — 표시명 120자는 들어간다
            assertThat(jdbcTemplate.queryForObject("SELECT CHAR_LENGTH(title) FROM product_options WHERE product_id = ?", Integer.class, solo))
                    .isEqualTo(120);
        }

        @Test
        @DisplayName("옵션 수정 — 가격은 수동 고정, 상태는 판매 중지 · 재개. 다른 상품의 옵션 404, 빈 본문 400. 사전예약은 오픈 3분 전부터 가격 · 되돌리기 · 판매 상태 전부 409")
        void editVariantPriceAndStatus() throws Exception {
            long productId = registerInStock();
            long variantId = variantIdOf(productId, "블랙 / 256GB");
            JsonNode priced = data(editVariant(productId, variantId, "{ \"price\": 1234000 }").andExpect(status().isOk()));
            assertThat(priced.get("price").decimalValue()).isEqualByComparingTo("1234000");
            edit(productId, "{ \"basePrice\": 900000 }").andExpect(status().isOk());
            assertThat(prices(productId)).containsEntry("블랙 / 256GB", "1234000").containsEntry("화이트 / 256GB", "900000");

            // 자동 계산으로 되돌리기 — 지금 기본가 + 추가금이 되고, 이후 기본가를 따라 움직인다
            JsonNode reset = data(editVariant(productId, variantId, "{ \"resetPrice\": true }").andExpect(status().isOk()));
            assertThat(reset.get("price").decimalValue()).isEqualByComparingTo("900000");
            edit(productId, "{ \"basePrice\": 950000 }").andExpect(status().isOk());
            assertThat(prices(productId)).as("되돌린 옵션은 다시 재계산에 들어간다").containsEntry("블랙 / 256GB", "950000");
            long manual512 = variantIdOf(productId, "블랙 / 512GB");
            JsonNode reset512 = data(editVariant(productId, manual512, "{ \"resetPrice\": true }").andExpect(status().isOk()));
            assertThat(reset512.get("price").decimalValue()).as("기본가 950,000 + 512GB 추가금 200,000").isEqualByComparingTo("1150000");
            expectValidation(editVariant(productId, variantId, "{ \"price\": 1, \"resetPrice\": true }"), "resetPrice");
            expectValidation(editVariant(productId, variantId, "{ \"resetPrice\": false }"), "body");

            JsonNode paused = data(editVariant(productId, variantId, "{ \"status\": \"PAUSED\" }").andExpect(status().isOk()));
            assertThat(paused.get("status").asString()).isEqualTo("PAUSED");
            expectValidation(editVariant(productId, variantId, "{}"), "body");
            expectValidation(editVariant(productId, variantId, "{ \"sku\": \"NEW\" }"), "sku");
            long other = registerInStock();
            editVariant(other, variantId, "{ \"status\": \"ACTIVE\" }").andExpect(status().isNotFound());

            long preorder = registerPreorder();
            long preorderVariant = variantIdOf(preorder, "블랙 / 256GB");
            Instant now = Instant.now();
            fixtures.campaign(preorder, now.minus(HOUR), now.plus(HOUR));
            editVariant(preorder, preorderVariant, "{ \"price\": 1 }").andExpect(status().isConflict());
            editVariant(preorder, preorderVariant, "{ \"resetPrice\": true }").andExpect(status().isConflict());
            editVariant(preorder, preorderVariant, "{ \"status\": \"PAUSED\" }")
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM product_options WHERE id = ?", String.class, preorderVariant))
                    .as("오픈 뒤 판매 중지는 막혀 판매 상태 그대로").isEqualTo("ACTIVE");
            long notOpened = registerPreorder();
            long notOpenedVariant = variantIdOf(notOpened, "블랙 / 256GB");
            fixtures.campaign(notOpened, now.plus(HOUR), now.plus(HOUR.multipliedBy(2)));
            editVariant(notOpened, notOpenedVariant, "{ \"status\": \"PAUSED\" }").andExpect(status().isOk());   // 대조군 — 잠금 전에는 된다
            mockMvc.perform(admin(post(PATH + "/{id}/option-values", preorder)).content("{ \"axisKey\": \"color\", \"value\": \"레드\" }"))
                    .andExpect(status().isConflict());
            long storage512 = valueIdOf(preorder, "storage", "512GB");
            mockMvc.perform(admin(patch(PATH + "/{id}/option-values/{v}", preorder, storage512)).content("{ \"surcharge\": 1 }"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));
            mockMvc.perform(admin(post(PATH + "/{id}/variants", preorder)).content("{ \"selections\": { \"color\": \"화이트\", \"storage\": \"256GB\" } }"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));
            // 오픈 뒤에는 DB 를 읽어야 아는 400 사유(없는 정규화값 · 이미 있는 조합)보다 409 가 먼저다. 본문만 보고 아는 400(소수 금액 · 빈 본문 · 모르는 칸)은 잠금 전에 먼저 난다
            mockMvc.perform(admin(patch(PATH + "/{id}/option-values/{v}", preorder, storage512)).content("{ \"value\": \"256GB\" }"))
                    .andExpect(status().isConflict());
            mockMvc.perform(admin(post(PATH + "/{id}/variants", preorder)).content("{ \"selections\": { \"color\": \"블랙\", \"storage\": \"256GB\" } }"))
                    .andExpect(status().isConflict());
            assertThat(prices(preorder)).as("오픈 뒤 거절된 수정은 아무것도 남기지 않는다 — 조합은 셋 그대로, 512GB 추가금 반영 없음")
                    .containsOnlyKeys("블랙 / 256GB", "블랙 / 512GB", "화이트 / 512GB").containsEntry("화이트 / 512GB", "1200000");
        }

        @Test
        @DisplayName("값 수정 — 이름은 오타까지 고칠 수 있고 옵션 표시명 · 필터 속성 · 사진 묶음이 따라간다. 같은 축의 같은 값 · 용량 형식은 400. 추가금은 그 값을 고른 옵션만 재계산(수동 제외)")
        void editOptionValue() throws Exception {
            long productId = registerInStock();
            long storage512 = valueIdOf(productId, "storage", "512GB");
            JsonNode renamed = data(mockMvc.perform(admin(patch(PATH + "/{id}/option-values/{v}", productId, storage512))
                            .content("{ \"value\": \"512 GB\" }")).andExpect(status().isOk()));
            assertThat(titles(renamed)).contains("블랙 / 512 GB", "화이트 / 512 GB", "블랙 / 256GB");

            expectValidation(mockMvc.perform(admin(patch(PATH + "/{id}/option-values/{v}", productId, storage512)).content("{ \"value\": \"256gb\" }")), "value");
            expectValidation(mockMvc.perform(admin(patch(PATH + "/{id}/option-values/{v}", productId, storage512)).content("{ \"value\": \"big\" }")), "value");
            expectValidation(mockMvc.perform(admin(patch(PATH + "/{id}/option-values/{v}", productId, storage512)).content("{}")), "body");

            // 색상 오타 수정: 화이트 → Whtie → White. 표시명 · 필터 속성(preorder 가 복사하는 JSON) · 사진 묶음 키가 따라간다
            long white = valueIdOf(productId, "color", "화이트");
            mockMvc.perform(admin(patch(PATH + "/{id}/option-values/{v}", productId, white)).content("{ \"value\": \"Whtie\" }")).andExpect(status().isOk());
            JsonNode fixed = data(mockMvc.perform(admin(patch(PATH + "/{id}/option-values/{v}", productId, white)).content("{ \"value\": \"White\" }"))
                    .andExpect(status().isOk()));
            assertThat(titles(fixed)).contains("White / 256GB", "White / 512 GB").doesNotContain("화이트 / 256GB");
            assertThat(jdbcTemplate.queryForList("SELECT JSON_UNQUOTE(JSON_EXTRACT(filter_attributes, '$.color')) FROM product_options WHERE product_id = ? AND title LIKE 'White%'",
                    String.class, productId)).containsExactly("White", "White");
            List<String> bundles = new ArrayList<>();
            fixed.get("product").get("images").get("gallery").forEach(b -> bundles.add(b.get("bundleKey").asString()));
            assertThat(bundles).as("사진 묶음 키가 새 이름을 따라간다 — 옛 이름으로 남으면 사진이 어느 색상에도 안 붙는다").containsExactlyInAnyOrder("블랙", "White");
            // 대소문자만 다른 이름은 자기 자신이라 된다. 다른 값과 같다고 보는 이름(악센트 · 대소문자 · 전각)은 400
            mockMvc.perform(admin(patch(PATH + "/{id}/option-values/{v}", productId, white)).content("{ \"value\": \"WHITE\" }")).andExpect(status().isOk());
            expectValidation(mockMvc.perform(admin(patch(PATH + "/{id}/option-values/{v}", productId, white)).content("{ \"value\": \"블랙\" }")), "value");

            mockMvc.perform(admin(patch(PATH + "/{id}/option-values/{v}", productId, storage512)).content("{ \"surcharge\": 300000 }")).andExpect(status().isOk());
            assertThat(prices(productId)).as("512GB 를 고른 옵션만, 수동(블랙 / 512 GB)은 제외")
                    .containsEntry("블랙 / 512 GB", "1270000").containsEntry("WHITE / 512 GB", "1300000")
                    .containsEntry("블랙 / 256GB", "1000000").containsEntry("WHITE / 256GB", "1000000");

            long other = registerInStock();
            mockMvc.perform(admin(patch(PATH + "/{id}/option-values/{v}", other, storage512)).content("{ \"surcharge\": 1 }")).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("상품 판매 상태 · 공개 여부")
    class SaleStatusAndVisibility {

        @Test
        @DisplayName("일반 상품 판매 중지는 회원 목록에서 빠지고 상세는 200 에 PAUSED 다. 같은 상태를 다시 보내도 200, 재개하면 목록에 돌아온다")
        void inStockPauseAndResume() throws Exception {
            long productId = readyInStock();
            String tag = tagOf(productId);
            assertThat(memberListIds(tag)).containsExactly(productId);

            JsonNode paused = data(saleStatus(productId, "PAUSED").andExpect(status().isOk()));
            assertThat(paused.get("productId").asLong()).isEqualTo(productId);
            assertThat(paused.get("status").asString()).isEqualTo("PAUSED");
            assertThat(paused.get("campaignCancellationRequested").asBoolean()).isFalse();
            assertThat(memberListIds(tag)).as("판매 중지는 목록에서 숨긴다").isEmpty();
            assertThat(data(memberDetail(productId).andExpect(status().isOk())).get("status").asString()).as("직접 상세는 판매 중지로 보인다")
                    .isEqualTo("PAUSED");
            assertThat(data(adminDetail(productId)).get("product").get("status").asString()).isEqualTo("PAUSED");

            saleStatus(productId, "PAUSED").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PAUSED"));
            saleStatus(productId, "ACTIVE").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTIVE"));
            assertThat(memberListIds(tag)).containsExactly(productId);
        }

        @Test
        @DisplayName("사전예약 판매 상태는 오픈 3분 전까지만 바꾼다 — 오픈 5분 전 · 회차 없음은 200, 오픈 2분 전은 409 · 오픈 뒤 ACTIVE 는 409 이고 상태는 그대로")
        void preorderStatusFreezesThreeMinutesBeforeOpen() throws Exception {
            Instant now = Instant.now();
            long noCampaign = registerPreorder();
            saleStatus(noCampaign, "PAUSED").andExpect(status().isOk());

            long beforeFreeze = registerPreorder();
            fixtures.campaign(beforeFreeze, now.plus(Duration.ofMinutes(5)), now.plus(HOUR));
            saleStatus(beforeFreeze, "PAUSED").andExpect(status().isOk());
            saleStatus(beforeFreeze, "ACTIVE").andExpect(status().isOk());

            long frozen = registerPreorder();
            fixtures.campaign(frozen, now.plus(Duration.ofMinutes(2)), now.plus(HOUR));
            saleStatus(frozen, "PAUSED").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"))
                    .andExpect(jsonPath("$.error.message").value("사전예약 오픈 3분 전부터는 판매 상태를 바꿀 수 없습니다."));
            // 잠금 판정이 먼저다 — 지금과 같은 상태를 보내도, 사유를 실어 보내도 409
            saleStatus(frozen, "ACTIVE").andExpect(status().isConflict());
            saleStatusBody(frozen, "{ \"status\": \"PAUSED\", \"reason\": \"x\" }").andExpect(status().isConflict());
            assertThat(statusOf(frozen)).isEqualTo("ACTIVE");

            // 오픈 뒤 PAUSED 는 회차 취소라 사유가 필요하고(아래 시험), ACTIVE 는 되돌리기라 409
            long opened = registerPreorder();
            fixtures.campaign(opened, now.minus(HOUR), now.plus(HOUR));
            saleStatus(opened, "ACTIVE").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));
            expectValidation(saleStatus(opened, "PAUSED"), "reason");
            assertThat(statusOf(opened)).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("사전예약 오픈 뒤 판매 중지(사유)는 회차 취소 접수 — 202 · PAUSED · 취소 시각, preorder-events 로 갈 이벤트 하나. 다시 보내도 202 이고 이벤트는 하나, 되돌리기는 409")
        void cancelCampaignAfterOpen() throws Exception {
            long productId = registerPreorder();
            fixtures.campaign(productId, Instant.now().minus(HOUR), Instant.now().plus(HOUR));

            JsonNode accepted = data(saleStatusBody(productId, "{ \"status\": \"PAUSED\", \"reason\": \"  공급 중단  \" }")
                    .andExpect(status().isAccepted()));
            assertThat(accepted.get("status").asString()).isEqualTo("PAUSED");
            assertThat(accepted.get("campaignCancellationRequested").asBoolean()).isTrue();
            assertThat(statusOf(productId)).isEqualTo("PAUSED");
            assertThat(jdbcTemplate.queryForObject("SELECT campaign_canceled_at IS NOT NULL FROM products WHERE id = ?", Boolean.class, productId))
                    .isTrue();
            List<java.util.Map<String, Object>> events = cancelEvents(productId);
            assertThat(events).hasSize(1);
            JsonNode payload = JSON.readTree((String) events.get(0).get("payload"));
            assertThat(payload.get("productId").asLong()).as("preorder 가 payload 의 productId 로 읽는다").isEqualTo(productId);
            assertThat(payload.get("reason").asString()).as("앞뒤 공백을 걷는다").isEqualTo("공급 중단");
            assertThat(events.get(0)).containsEntry("aggregate_type", "PRODUCT").containsEntry("aggregate_id", productId);

            saleStatusBody(productId, "{ \"status\": \"PAUSED\", \"reason\": \"다시\" }").andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.data.campaignCancellationRequested").value(true));
            assertThat(cancelEvents(productId)).as("이미 취소된 상품 — 이벤트를 다시 적지 않는다").hasSize(1);
            saleStatus(productId, "ACTIVE").andExpect(status().isConflict());
            assertThat(statusOf(productId)).isEqualTo("PAUSED");
            // DB 도 막는다 — 취소 표식이 있는 행은 ACTIVE 가 될 수 없고, 일반 상품에는 표식이 붙을 수 없다(CHECK)
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update("UPDATE products SET status = 'ACTIVE' WHERE id = ?", productId))
                    .hasMessageContaining("ck_product_campaign_canceled");
            long inStock = registerInStock();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                            "UPDATE products SET status = 'PAUSED', campaign_canceled_at = UTC_TIMESTAMP(6) WHERE id = ?", inStock))
                    .hasMessageContaining("ck_product_campaign_canceled");
        }

        @Test
        @DisplayName("회차가 취소된 상품은 취소 뒤 오픈이 미래로 옮겨져도 그대로 취소다 — 되돌리기 · 정보 수정은 409, 취소 재요청은 202 이고 이벤트는 하나")
        void canceledProductStaysCanceledWhenOpenMovesLater() throws Exception {
            long productId = registerPreorder();
            fixtures.campaign(productId, Instant.now().minus(HOUR), Instant.now().plus(HOUR));
            saleStatusBody(productId, "{ \"status\": \"PAUSED\", \"reason\": \"공급 중단\" }").andExpect(status().isAccepted());
            fixtures.moveCampaignOpensAt(productId, Instant.now().plus(Duration.ofMinutes(30)));

            saleStatus(productId, "ACTIVE").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("STATE_CONFLICT"));
            edit(productId, "{ \"title\": \"취소된 상품\" }").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.message").value("회차가 취소된 상품은 바꿀 수 없습니다."));
            saleStatusBody(productId, "{ \"status\": \"PAUSED\", \"reason\": \"다시\" }").andExpect(status().isAccepted());
            assertThat(statusOf(productId)).isEqualTo("PAUSED");
            assertThat(cancelEvents(productId)).hasSize(1);
        }

        @Test
        @DisplayName("오픈 전에 판매 중지로 둔 채 오픈을 넘긴 상품은 회차 취소가 아니다 — 취소 시각 · 이벤트 없음. 관리자가 사유와 함께 판매 중지를 보내면 그때 취소된다")
        void pausedBeforeOpenIsNotACancellationUntilExplicit() throws Exception {
            long productId = registerPreorder();
            fixtures.campaign(productId, Instant.now().plus(Duration.ofMinutes(10)), Instant.now().plus(HOUR));
            saleStatus(productId, "PAUSED").andExpect(status().isOk()).andExpect(jsonPath("$.data.campaignCancellationRequested").value(false));
            fixtures.moveCampaignOpensAt(productId, Instant.now().minus(Duration.ofMinutes(1)));

            assertThat(jdbcTemplate.queryForObject("SELECT campaign_canceled_at IS NULL FROM products WHERE id = ?", Boolean.class, productId))
                    .isTrue();
            assertThat(cancelEvents(productId)).isEmpty();

            saleStatusBody(productId, "{ \"status\": \"PAUSED\", \"reason\": \"회차 운영 취소\" }").andExpect(status().isAccepted());
            assertThat(cancelEvents(productId)).hasSize(1);
        }

        @Test
        @DisplayName("사유는 사전예약 오픈 뒤 판매 중지에만 — 일반 상품 · 오픈 전 사전예약은 400(빈 사유는 보내지 않은 것), 오픈 뒤 빈 사유 · 501자는 400, 앞뒤 공백을 뺀 500자는 202")
        void reasonRules() throws Exception {
            long inStock = registerInStock();
            expectValidation(saleStatusBody(inStock, "{ \"status\": \"PAUSED\", \"reason\": \"x\" }"), "reason");
            saleStatusBody(inStock, "{ \"status\": \"PAUSED\", \"reason\": \"\" }").andExpect(status().isOk());
            long fullLength = registerPreorder();
            fixtures.campaign(fullLength, Instant.now().minus(HOUR), Instant.now().plus(HOUR));
            saleStatusBody(fullLength, "{ \"status\": \"PAUSED\", \"reason\": \"  " + "가".repeat(500) + "  \" }").andExpect(status().isAccepted());
            long notOpened = registerPreorder();
            fixtures.campaign(notOpened, Instant.now().plus(HOUR), Instant.now().plus(HOUR.multipliedBy(2)));
            expectValidation(saleStatusBody(notOpened, "{ \"status\": \"PAUSED\", \"reason\": \"x\" }"), "reason");
            long opened = registerPreorder();
            fixtures.campaign(opened, Instant.now().minus(HOUR), Instant.now().plus(HOUR));
            expectValidation(saleStatusBody(opened, "{ \"status\": \"PAUSED\", \"reason\": \"   \" }"), "reason");
            expectValidation(saleStatusBody(opened, "{ \"status\": \"PAUSED\", \"reason\": \"" + "가".repeat(501) + "\" }"), "reason");
            // 길이는 UTF-16 단위 — 이모지 300 개(600)는 preorder 가 자르게 되므로 받지 않는다
            expectValidation(saleStatusBody(opened, "{ \"status\": \"PAUSED\", \"reason\": \"" + "😀".repeat(300) + "\" }"), "reason");
            assertThat(cancelEvents(opened)).isEmpty();
            assertThat(statusOf(opened)).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("판매 상태 입력이 틀리면 그 칸의 400 — 빈 본문 · 모르는 상태 · 모르는 칸. 없는 상품은 404")
        void saleStatusInputErrors() throws Exception {
            long productId = registerInStock();
            expectValidation(saleStatusBody(productId, "{}"), "status");
            expectValidation(saleStatusBody(productId, "{ \"status\": \"STOPPED\" }"), "status");
            expectValidation(saleStatusBody(productId, "{ \"status\": \"PAUSED\", \"reason\": \"x\" }"), "reason");
            // 숫자 · 숫자 문자열을 enum 순번으로 읽지 않는다 — 1 이 조용히 PAUSED 가 되면 안 된다
            expectValidation(saleStatusBody(productId, "{ \"status\": 1 }"), "status");
            expectValidation(saleStatusBody(productId, "{ \"status\": \"1\" }"), "status");
            assertThat(statusOf(productId)).isEqualTo("ACTIVE");
            saleStatus(999_999_999L, "PAUSED").andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("비공개로 바꾸면 회원 목록 · 상세에서 사라지고(상세 404) 다시 공개하면 돌아온다. 사전예약 오픈 뒤에도 바꿀 수 있다")
        void visibilityToggle() throws Exception {
            long productId = readyInStock();
            String tag = tagOf(productId);

            JsonNode hidden = data(visibility(productId, false).andExpect(status().isOk()));
            assertThat(hidden.get("productId").asLong()).isEqualTo(productId);
            assertThat(hidden.get("visible").asBoolean()).isFalse();
            assertThat(memberListIds(tag)).isEmpty();
            memberDetail(productId).andExpect(status().isNotFound());

            visibility(productId, true).andExpect(status().isOk()).andExpect(jsonPath("$.data.visible").value(true));
            assertThat(memberListIds(tag)).containsExactly(productId);
            memberDetail(productId).andExpect(status().isOk());

            long opened = registerPreorder();
            fixtures.campaign(opened, Instant.now().minus(HOUR), Instant.now().plus(HOUR));
            visibility(opened, false).andExpect(status().isOk());
            assertThat(jdbcTemplate.queryForObject("SELECT visible FROM products WHERE id = ?", Boolean.class, opened)).isFalse();

            expectValidation(mockMvc.perform(admin(patch(PATH + "/{id}/visibility", productId)).content("{}")), "visible");
            // boolean 칸은 true · false 만 — 문자열 · 숫자를 바꿔 읽지 않는다
            for (String body : new String[] {"{ \"visible\": \"false\" }", "{ \"visible\": 0 }", "{ \"visible\": 1 }"}) {
                expectValidation(mockMvc.perform(admin(patch(PATH + "/{id}/visibility", productId)).content(body)), "visible");
            }
            visibility(999_999_999L, false).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("관리자만 — 회원 403, 익명 401")
        void adminOnly() throws Exception {
            long productId = registerInStock();
            assertThat(visibleOf(productId)).isTrue();
            for (String path : new String[] {"/{id}/sale-status", "/{id}/visibility"}) {
                String body = path.endsWith("sale-status") ? "{ \"status\": \"PAUSED\" }" : "{ \"visible\": false }";
                mockMvc.perform(patch(PATH + path, productId).contentType(MediaType.APPLICATION_JSON).content(body)
                        .with(user("657").roles("USER"))).andExpect(status().isForbidden());
                mockMvc.perform(patch(PATH + path, productId).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isUnauthorized());
            }
            assertThat(statusOf(productId)).isEqualTo("ACTIVE");
            assertThat(visibleOf(productId)).as("거절된 공개 전환은 공개 여부를 바꾸지 않는다").isTrue();
        }

        @Test
        @DisplayName("사전예약 상품을 고치면 수정마다 PREORDER_PRODUCT_CHANGED 하나(빈 payload · aggregateId 상품 id) — 같은 상태 · 같은 공개 여부 · 400 · 오픈 3분 전의 409 는 없고, 그때도 공개 전환은 보낸다")
        void preorderEditsNotifyPreorder() throws Exception {
            long productId = registerPreorder();
            assertThat(changedEvents(productId)).as("등록은 이 이벤트가 아니다").isEmpty();

            edit(productId, "{ \"title\": \"Nova 1 Pro\" }").andExpect(status().isOk());
            List<java.util.Map<String, Object>> events = changedEvents(productId);
            assertThat(events).hasSize(1);
            assertThat(events.get(0)).containsEntry("aggregate_type", "PRODUCT").containsEntry("aggregate_id", productId);
            assertThat(JSON.readTree((String) events.get(0).get("payload")).isEmpty()).as("payload 는 빈 객체 — 상품 id 는 봉투에만").isTrue();

            mockMvc.perform(admin(post(PATH + "/{id}/option-values", productId)).content("{ \"axisKey\": \"color\", \"value\": \"레드\" }"))
                    .andExpect(status().isCreated());
            assertThat(changedEvents(productId)).hasSize(2);
            long storage512 = valueIdOf(productId, "storage", "512GB");
            mockMvc.perform(admin(patch(PATH + "/{id}/option-values/{v}", productId, storage512)).content("{ \"surcharge\": 300000 }"))
                    .andExpect(status().isOk());
            assertThat(changedEvents(productId)).hasSize(3);
            mockMvc.perform(admin(post(PATH + "/{id}/variants", productId)).content("{ \"selections\": { \"color\": \"화이트\", \"storage\": \"256GB\" } }"))
                    .andExpect(status().isCreated());
            assertThat(changedEvents(productId)).hasSize(4);
            editVariant(productId, variantIdOf(productId, "블랙 / 256GB"), "{ \"price\": 990000 }").andExpect(status().isOk());
            assertThat(changedEvents(productId)).hasSize(5);

            saleStatus(productId, "PAUSED").andExpect(status().isOk());
            saleStatus(productId, "PAUSED").andExpect(status().isOk());
            assertThat(changedEvents(productId)).as("같은 상태를 다시 보내면 바뀐 것이 없다").hasSize(6);
            saleStatus(productId, "ACTIVE").andExpect(status().isOk());
            visibility(productId, false).andExpect(status().isOk());
            visibility(productId, false).andExpect(status().isOk());
            assertThat(changedEvents(productId)).as("같은 공개 여부를 다시 보내면 바뀐 것이 없다").hasSize(8);

            // 재계산이 상한을 넘어 400 — 실패한 수정은 이벤트를 남기지 않는다
            expectValidation(edit(productId, "{ \"basePrice\": 999999999999 }"), "basePrice");
            assertThat(changedEvents(productId)).hasSize(8);

            fixtures.campaign(productId, Instant.now().plus(Duration.ofMinutes(2)), Instant.now().plus(HOUR));
            edit(productId, "{ \"title\": \"Nova 1 Max\" }").andExpect(status().isConflict());
            assertThat(changedEvents(productId)).hasSize(8);
            visibility(productId, true).andExpect(status().isOk());
            assertThat(changedEvents(productId)).as("오픈 3분 전에도 공개 전환은 바로 알린다").hasSize(9);
        }

        @Test
        @DisplayName("일반 상품 수정과 사전예약 회차 취소는 PREORDER_PRODUCT_CHANGED 를 적지 않는다 — 회차 취소는 자기 이벤트로 preorder 가 캐시를 비운다")
        void inStockEditsAndCampaignCancelDoNotNotifyChange() throws Exception {
            long inStock = registerInStock();
            edit(inStock, "{ \"title\": \"Nova 1 Pro\" }").andExpect(status().isOk());
            saleStatus(inStock, "PAUSED").andExpect(status().isOk());
            visibility(inStock, false).andExpect(status().isOk());
            assertThat(changedEvents(inStock)).isEmpty();

            long canceled = registerPreorder();
            fixtures.campaign(canceled, Instant.now().minus(HOUR), Instant.now().plus(HOUR));
            saleStatusBody(canceled, "{ \"status\": \"PAUSED\", \"reason\": \"공급 중단\" }").andExpect(status().isAccepted());
            assertThat(cancelEvents(canceled)).hasSize(1);
            assertThat(changedEvents(canceled)).isEmpty();
        }

        /** 공개 · 판매 중 · 재고 행이 있는(준비된) 일반 상품. 회원 목록에서 이 상품만 고르도록 고유 태그를 붙인다. */
        private long readyInStock() throws Exception {
            long productId = registerInStock();
            fixtures.stockReady(productId);
            edit(productId, "{ \"tags\": \"" + tagOf(productId) + "\" }").andExpect(status().isOk());
            return productId;
        }

        private String tagOf(long productId) {
            return "status-" + productId;
        }

        private List<Long> memberListIds(String tag) throws Exception {
            JsonNode items = JSON.readTree(mockMvc.perform(get("/api/v1/products").param("q", tag).param("size", "100"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data/items");
            List<Long> ids = new ArrayList<>();
            items.forEach(item -> ids.add(item.get("productId").asLong()));
            return ids;
        }

        private ResultActions memberDetail(long productId) throws Exception {
            return mockMvc.perform(get("/api/v1/products/{id}", productId));
        }

        private String statusOf(long productId) {
            return jdbcTemplate.queryForObject("SELECT status FROM products WHERE id = ?", String.class, productId);
        }

        private List<java.util.Map<String, Object>> cancelEvents(long productId) {
            return jdbcTemplate.queryForList("""
                    SELECT aggregate_type, aggregate_id, payload FROM catalog_outbox_events
                     WHERE aggregate_id = ? AND event_type = 'PREORDER_CAMPAIGN_CANCELED'
                    """, productId);
        }

        private List<java.util.Map<String, Object>> changedEvents(long productId) {
            return jdbcTemplate.queryForList("""
                    SELECT aggregate_type, aggregate_id, payload FROM catalog_outbox_events
                     WHERE aggregate_id = ? AND event_type = 'PREORDER_PRODUCT_CHANGED' ORDER BY id
                    """, productId);
        }

        private boolean visibleOf(long productId) {
            return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT visible FROM products WHERE id = ?", Boolean.class, productId));
        }

        private ResultActions saleStatus(long productId, String status) throws Exception {
            return saleStatusBody(productId, "{ \"status\": \"" + status + "\" }");
        }

        private ResultActions saleStatusBody(long productId, String body) throws Exception {
            return mockMvc.perform(admin(patch(PATH + "/{id}/sale-status", productId)).content(body));
        }

        private ResultActions visibility(long productId, boolean visible) throws Exception {
            return mockMvc.perform(admin(patch(PATH + "/{id}/visibility", productId)).content("{ \"visible\": " + visible + " }"));
        }
    }

    @Test
    @DisplayName("관리자만 — 회원 403, 익명 401")
    void adminOnly() throws Exception {
        long productId = registerInStock();
        mockMvc.perform(patch(PATH + "/{id}", productId).contentType(MediaType.APPLICATION_JSON).content("{ \"title\": \"x\" }")
                .with(user("657").roles("USER"))).andExpect(status().isForbidden());
        mockMvc.perform(patch(PATH + "/{id}", productId).contentType(MediaType.APPLICATION_JSON).content("{ \"title\": \"x\" }"))
                .andExpect(status().isUnauthorized());
    }

    // ── 도우미 ─────────────────────────────────────────────────────────────

    /** 축 color(블랙 · 화이트) · storage(256GB +0 · 512GB +200000), 기본가 1,000,000, 블랙/512GB 만 수동 1,270,000(어떤 재계산 결과와도 겹치지 않는 값 — 겹치면 수동 보호가 빠져도 시험이 못 가른다). 재고 각 3. */
    private long registerInStock() throws Exception {
        return register("IN_STOCK", """
                "combinations": [
                  { "selections": { "color": "블랙", "storage": "256GB" }, "stock": 3 },
                  { "selections": { "color": "블랙", "storage": "512GB" }, "stock": 3, "price": 1270000 },
                  { "selections": { "color": "화이트", "storage": "256GB" }, "stock": 3 },
                  { "selections": { "color": "화이트", "storage": "512GB" }, "stock": 3 } ],
                """);
    }

    /** 같은 축 · 기본가, 블랙/512GB 만 수동 1,300,000, 화이트/256GB 는 만들지 않는다(조합 추가 시험용). 회차는 2시간 뒤. */
    private long registerPreorder() throws Exception {
        Instant opensAt = Instant.now().plus(HOUR.multipliedBy(2));
        return register("PREORDER", """
                "combinations": [ { "selections": { "color": "블랙", "storage": "512GB" }, "price": 1300000 },
                                  { "selections": { "color": "화이트", "storage": "256GB" }, "excluded": true } ],
                "campaign": { "opensAt": "%s", "closesAt": "%s" },
                "shipmentBatches": [ { "batchNumber": 1, "positionFrom": 1, "positionTo": null, "estimatedShipStart": "2026-11-01", "estimatedShipEnd": "2026-11-07" } ],
                """.formatted(opensAt, opensAt.plus(Duration.ofDays(3))));
    }

    private long register(String saleMode, String extra) throws Exception {
        String body = """
                {
                  "categoryId": %d, "saleMode": "%s", "title": "Nova 1", "description": "설명", "tags": "nova", "visible": true,
                  "basePrice": 1000000,
                  "optionAxes": [
                    { "key": "color", "label": "색상", "values": [ { "value": "블랙" }, { "value": "화이트" } ] },
                    { "key": "storage", "label": "용량", "values": [ { "value": "256GB" }, { "value": "512GB", "surcharge": 200000 } ] } ],
                  %s
                  "images": { "gallery": [ { "color": "블랙", "items": [ { "url": "https://img/b.jpg" } ] }, { "color": "화이트", "items": [ { "url": "https://img/w.jpg" } ] } ] }
                }
                """.formatted(categoryId, saleMode, extra);
        ResultActions created = mockMvc.perform(admin(post(PATH)).header("Idempotency-Key", "k-" + ShopFixtures.unique()).content(body))
                .andExpect(status().isCreated());
        return data(created).get("registration").get("productId").asLong();
    }

    private long registerRaw(String body) throws Exception {
        ResultActions created = mockMvc.perform(admin(post(PATH)).header("Idempotency-Key", "k-" + ShopFixtures.unique()).content(body))
                .andExpect(status().isCreated());
        return data(created).get("registration").get("productId").asLong();
    }

    private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder builder) {
        return builder.contentType(MediaType.APPLICATION_JSON).with(user("admin").roles("ADMIN"));
    }

    private ResultActions edit(long productId, String body) throws Exception {
        return mockMvc.perform(admin(patch(PATH + "/{id}", productId)).content(body));
    }

    private ResultActions editVariant(long productId, long variantId, String body) throws Exception {
        return mockMvc.perform(admin(patch(PATH + "/{id}/variants/{v}", productId, variantId)).content(body));
    }

    private ResultActions adminDetail(long productId) throws Exception {
        return mockMvc.perform(get(PATH + "/{id}", productId).with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
    }

    private java.util.Map<String, String> prices(long productId) throws Exception {
        java.util.Map<String, String> byTitle = new java.util.LinkedHashMap<>();
        for (JsonNode variant : data(adminDetail(productId)).get("product").get("variants")) {
            byTitle.put(variant.get("title").asString(), variant.get("price").decimalValue().toPlainString());
        }
        return byTitle;
    }

    private static List<String> titles(JsonNode detail) {
        List<String> titles = new ArrayList<>();
        detail.get("product").get("variants").forEach(v -> titles.add(v.get("title").asString()));
        return titles;
    }

    private long variantIdOf(long productId, String title) throws Exception {
        for (JsonNode variant : data(adminDetail(productId)).get("product").get("variants")) {
            if (variant.get("title").asString().equals(title)) {
                return variant.get("variantId").asLong();
            }
        }
        throw new AssertionError("no variant " + title);
    }

    private long valueIdOf(long productId, String axisKey, String normalized) {
        return jdbcTemplate.queryForObject("""
                SELECT v.id FROM product_option_values v JOIN product_option_axes a ON a.id = v.axis_id
                 WHERE a.product_id = ? AND a.axis_key = ? AND v.normalized_value = ?
                """, Long.class, productId, axisKey, normalized);
    }

    private static void expectValidation(ResultActions actions, String field) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value(field));
    }

    private static JsonNode data(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("data");
    }
}
