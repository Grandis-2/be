package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.UuidBinary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 계약 contracts/order-internal.md 의 옵션 일괄 조회(GET /internal/options)를 응답 모양 그대로 본다.
 * 실제 토큰의 통과 · 거절은 TokenAuthenticationApiTest 가 본다(같은 /internal/** 규칙).
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
class InternalOptionApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @Test
    @DisplayName("옵션마다 상품의 사실을 계약의 칸 이름으로 — 요청 순서, 없는 id 는 빠지고 같은 id 는 한 번. 보증 · 썸네일은 옵션 문서 · 상품 칸에서")
    void returnsFactsInRequestOrder() throws Exception {
        UUID phone = fixtures.product("IN_STOCK", "ACTIVE");
        fixtures.image(phone, "GALLERY", "블랙", 0, false, "https://img/black-0.jpg");
        jdbcTemplate.update("UPDATE products SET options = JSON_SET(options, '$.warranty', JSON_OBJECT('offered', TRUE, 'surcharge', 199000)) WHERE id = ?",
                (Object) UuidBinary.toBytes(phone));
        UUID black = fixtures.option(phone, "ACTIVE", new BigDecimal("1250000"));
        fixtures.inventory(black, 5, 0, 0);
        UUID cable = fixtures.product("IN_STOCK", "ACTIVE");
        UUID cableOption = fixtures.option(cable, "ACTIVE", new BigDecimal("9000"));
        UUID missing = UUID.randomUUID();

        asUser(List.of(cableOption, missing, black, cableOption))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].optionId").value(cableOption.toString()))
                .andExpect(jsonPath("$.data.items[0].warranty.offered").value(false))
                .andExpect(jsonPath("$.data.items[0].warranty.surcharge").value(0))
                .andExpect(jsonPath("$.data.items[0].imageUrl").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].registrationCompleted").value(false))
                .andExpect(jsonPath("$.data.items[1].optionId").value(black.toString()))
                .andExpect(jsonPath("$.data.items[1].productId").value(phone.toString()))
                .andExpect(jsonPath("$.data.items[1].productTitle").value("Nova 1"))
                .andExpect(jsonPath("$.data.items[1].optionTitle").value("옵션"))
                .andExpect(jsonPath("$.data.items[1].sku").isString())
                .andExpect(jsonPath("$.data.items[1].price").value(1250000))
                .andExpect(jsonPath("$.data.items[1].optionStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.items[1].saleMode").value("IN_STOCK"))
                .andExpect(jsonPath("$.data.items[1].productStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.items[1].visible").value(true))
                .andExpect(jsonPath("$.data.items[1].registrationCompleted").value(true))
                .andExpect(jsonPath("$.data.items[1].warranty.offered").value(true))
                .andExpect(jsonPath("$.data.items[1].warranty.surcharge").value(199000))
                .andExpect(jsonPath("$.data.items[1].imageUrl").value("https://img/black-0.jpg"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("비공개 · 판매 중지 · 사전예약도 200 으로 사실 그대로 — 살 수 있는지는 호출자가 가린다")
    void hiddenPausedAndPreorderAreStillReturned() throws Exception {
        UUID hidden = fixtures.product("IN_STOCK", "PAUSED");
        jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = ?", (Object) UuidBinary.toBytes(hidden));
        UUID pausedOption = fixtures.option(hidden, "PAUSED", new BigDecimal("1000"));
        UUID preorder = fixtures.product("PREORDER", "ACTIVE");
        UUID preorderOption = fixtures.option(preorder, "ACTIVE", new BigDecimal("1000"));

        asUser(List.of(pausedOption, preorderOption))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].visible").value(false))
                .andExpect(jsonPath("$.data.items[0].productStatus").value("PAUSED"))
                .andExpect(jsonPath("$.data.items[0].optionStatus").value("PAUSED"))
                .andExpect(jsonPath("$.data.items[1].saleMode").value("PREORDER"));
    }

    @Test
    @DisplayName("ids 는 1~50개(같은 id 는 한 번 셈) — 50 은 200, 51 은 400(field=ids). 비었거나 · 없거나 · UUID 가 아니거나 · 빈 원소가 있으면 400")
    void idsMustBeOneToFifty() throws Exception {
        List<UUID> fifty = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            fifty.add(UUID.randomUUID());
        }
        asUser(fifty).andExpect(status().isOk()).andExpect(jsonPath("$.data.items", hasSize(0)));
        List<UUID> fiftyWithDuplicate = new ArrayList<>(fifty);
        fiftyWithDuplicate.add(fifty.getFirst());
        asUser(fiftyWithDuplicate).andExpect(status().isOk());

        List<UUID> fiftyOne = new ArrayList<>(fifty);
        fiftyOne.add(UUID.randomUUID());
        expectIdsViolation(asUser(fiftyOne));
        expectIdsViolation(mockMvc.perform(get("/internal/options").param("ids", "").with(user("657").roles("USER"))));
        expectIdsViolation(mockMvc.perform(get("/internal/options").with(user("657").roles("USER"))));
        expectIdsViolation(mockMvc.perform(get("/internal/options").param("ids", "not-a-uuid").with(user("657").roles("USER"))));
        // 빈 원소(끝 쉼표 · 쉼표만)는 null 로 들어온다 — 세지 않고 400
        expectIdsViolation(mockMvc.perform(get("/internal/options").param("ids", fifty.getFirst() + ",").with(user("657").roles("USER"))));
        expectIdsViolation(mockMvc.perform(get("/internal/options").param("ids", ",").with(user("657").roles("USER"))));
    }

    @Test
    @DisplayName("같은 ids 를 반복 파라미터(ids=a&ids=b)로 보내도 쉼표(ids=a,b)와 같다")
    void repeatedParameterEqualsCommaSeparated() throws Exception {
        UUID productId = fixtures.product("IN_STOCK", "ACTIVE");
        UUID first = fixtures.option(productId, "ACTIVE", new BigDecimal("1000"));
        UUID second = fixtures.option(productId, "ACTIVE", new BigDecimal("2000"));

        mockMvc.perform(get("/internal/options").param("ids", first.toString()).param("ids", second.toString())
                        .with(user("657").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].optionId").value(first.toString()))
                .andExpect(jsonPath("$.data.items[1].optionId").value(second.toString()));
    }

    @Test
    @DisplayName("인증 없는 요청은 401, USER · ADMIN 은 200, 그 밖의 역할은 403")
    void requiresUserOrAdminRole() throws Exception {
        String ids = UUID.randomUUID().toString();
        mockMvc.perform(get("/internal/options").param("ids", ids)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/options").param("ids", ids).with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
        mockMvc.perform(get("/internal/options").param("ids", ids).with(user("x").roles("OTHER"))).andExpect(status().isForbidden());
    }

    private ResultActions asUser(List<UUID> ids) throws Exception {
        return mockMvc.perform(get("/internal/options")
                .param("ids", ids.stream().map(UUID::toString).collect(Collectors.joining(",")))
                .with(user("657").roles("USER")));
    }

    private static void expectIdsViolation(ResultActions actions) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("ids"));
    }
}
