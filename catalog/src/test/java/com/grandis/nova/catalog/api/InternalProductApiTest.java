package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 계약 contracts/preorder-internal.md 를 응답 모양 그대로 본다. 응답 모양 시험은 MockMvc 의 user() 로 인증된 요청을 만들고,
 * 실제 토큰(Bearer · 서명 · 만료 · 폐기)의 통과 · 거절은 TokenAuthenticationApiTest 가 본다.
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
class InternalProductApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    ShopFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @Test
    @DisplayName("상품과 옵션 전체를 계약의 칸 이름으로 돌려준다 — 판매 중지 옵션도 상태 그대로")
    void returnsProductWithAllOptions() throws Exception {
        Long productId = fixtures.product("PREORDER", "ACTIVE");
        Long active = fixtures.option(productId, "ACTIVE");
        Long paused = fixtures.option(productId, "PAUSED");

        asUser(productId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.productId").value(productId))
                .andExpect(jsonPath("$.data.title").value("Nova 1"))
                .andExpect(jsonPath("$.data.saleMode").value("PREORDER"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.visible").value(true))
                .andExpect(jsonPath("$.data.registrationCompleted").value(false))
                .andExpect(jsonPath("$.data.options", hasSize(2)))
                .andExpect(jsonPath("$.data.options[0].optionId").value(active))
                .andExpect(jsonPath("$.data.options[0].title").value("블랙 / 256GB"))
                .andExpect(jsonPath("$.data.options[0].price").value(ShopFixtures.OPTION_PRICE.intValue()))
                .andExpect(jsonPath("$.data.options[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.options[0].sku").isString())
                .andExpect(jsonPath("$.data.options[1].optionId").value(paused))
                .andExpect(jsonPath("$.data.options[1].status").value("PAUSED"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("비공개 · 준비 전 상품도 200 으로 돌려주고 두 칸이 그 상태를 말한다 — 숨김 판정은 호출자 몫. 사전예약의 준비는 회차 행")
    void hiddenAndIncompleteProductsAreStillReturned() throws Exception {
        Long productId = fixtures.product("PREORDER", "ACTIVE");
        jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = ?", productId);
        fixtures.registration(productId, ShopFixtures.unique());

        asUser(productId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visible").value(false))
                .andExpect(jsonPath("$.data.registrationCompleted").value(false));

        fixtures.campaign(productId, java.time.Instant.now().plusSeconds(3600), java.time.Instant.now().plusSeconds(7200));
        asUser(productId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.registrationCompleted").value(true));
    }

    @Test
    @DisplayName("옵션이 없는 상품은 404 가 아니라 빈 목록이다")
    void productWithoutOptionsReturnsEmptyList() throws Exception {
        Long productId = fixtures.product("IN_STOCK", "PAUSED");

        asUser(productId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAUSED"))
                .andExpect(jsonPath("$.data.options", hasSize(0)));
    }

    @Test
    @DisplayName("없는 상품은 404 PRODUCT_NOT_FOUND 봉투다")
    void unknownProductIsNotFound() throws Exception {
        asUser(999_999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    @DisplayName("없는 상품(PRODUCT_NOT_FOUND)과 틀린 경로(NOT_FOUND)는 코드로 갈린다 — 호출자가 배포 사고를 상품 없음으로 캐시하지 않게")
    void wrongPathIsPlainNotFound() throws Exception {
        Long productId = fixtures.product("IN_STOCK", "ACTIVE");

        mockMvc.perform(get("/internal/products/{id}/optionz", productId).with(user("657").roles("USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("인증 없는 요청은 401, USER · ADMIN 은 200, 그 밖의 역할은 403")
    void requiresUserOrAdminRole() throws Exception {
        Long productId = fixtures.product("IN_STOCK", "ACTIVE");

        mockMvc.perform(get("/internal/products/{id}/options", productId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/products/{id}/options", productId).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
        asUser(productId).andExpect(status().isOk());
        mockMvc.perform(get("/internal/products/{id}/options", productId).with(user("guest").roles("GUEST")))
                .andExpect(status().isForbidden());
    }

    private ResultActions asUser(Long productId) throws Exception {
        return mockMvc.perform(get("/internal/products/{id}/options", productId).with(user("657").roles("USER")));
    }
}
