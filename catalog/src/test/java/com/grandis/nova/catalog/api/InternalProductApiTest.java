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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

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
        UUID productId = fixtures.product("PREORDER", "ACTIVE");
        UUID active = fixtures.option(productId, "ACTIVE");
        UUID paused = fixtures.option(productId, "PAUSED");

        asUser(productId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.productId").value(productId.toString()))
                .andExpect(jsonPath("$.data.title").value("Nova 1"))
                .andExpect(jsonPath("$.data.saleMode").value("PREORDER"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.visible").value(true))
                .andExpect(jsonPath("$.data.registrationCompleted").value(true))   // 사전예약은 늘 true — 회차 행이 없어도(아래 시험)
                .andExpect(jsonPath("$.data.options", hasSize(2)))
                .andExpect(jsonPath("$.data.options[0].optionId").value(active.toString()))
                .andExpect(jsonPath("$.data.options[0].title").value("블랙 / 256GB"))
                .andExpect(jsonPath("$.data.options[0].price").value(ShopFixtures.OPTION_PRICE.intValue()))
                .andExpect(jsonPath("$.data.options[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.options[0].sku").isString())
                .andExpect(jsonPath("$.data.options[1].optionId").value(paused.toString()))
                .andExpect(jsonPath("$.data.options[1].status").value("PAUSED"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("비공개 · 준비 전 상품도 200 으로 돌려주고 두 칸이 그 상태를 말한다 — 숨김 판정은 호출자 몫. 일반의 준비는 판매 준비 이벤트, 사전예약은 늘 true")
    void hiddenAndIncompleteProductsAreStillReturned() throws Exception {
        UUID productId = fixtures.product("IN_STOCK", "ACTIVE");
        jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = ?", (Object) UuidBinary.toBytes(productId));
        fixtures.registration(productId, ShopFixtures.unique());

        asUser(productId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visible").value(false))
                .andExpect(jsonPath("$.data.registrationCompleted").value(false));

        fixtures.markStockReady(productId);
        asUser(productId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.registrationCompleted").value(true));

        // 사전예약은 회차 행이 없어도 true — 이 응답은 preorder 접수가 동기로 부르므로 preorder 에 묻지 않는다(순환 호출). preorder 는 이 칸을 판정에 쓰지 않는다
        UUID preorder = fixtures.product("PREORDER", "ACTIVE");
        fixtures.registration(preorder, ShopFixtures.unique());
        asUser(preorder)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.registrationCompleted").value(true));
    }

    @Test
    @DisplayName("옵션 문서를 풀지 못해도(모양이 어긋남 · JSON null) 응답은 그대로 내고 옵션 사진만 비운다 — 사진은 표시용이라 접수 판정을 막지 않는다")
    void unreadableDocumentOnlyDropsImages() throws Exception {
        UUID productId = fixtures.product("PREORDER", "ACTIVE");
        UUID option = fixtures.option(productId, "ACTIVE");
        fixtures.image(productId, "GALLERY", "", 0, true, "https://img/default-0.jpg");
        asUser(productId).andExpect(jsonPath("$.data.options[0].imageUrl").value("https://img/default-0.jpg"));   // 대조군 — 정상 문서면 사진이 있다
        try {
            for (String broken : List.of("JSON_OBJECT('axes', 'not-a-list')", "CAST('null' AS JSON)")) {   // 모양이 어긋남 · JSON null
                jdbcTemplate.update("UPDATE products SET options = " + broken + " WHERE id = ?", (Object) UuidBinary.toBytes(productId));
                asUser(productId)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.options[0].optionId").value(option.toString()))
                        .andExpect(jsonPath("$.data.options[0].status").value("ACTIVE"))
                        .andExpect(jsonPath("$.data.options[0].imageUrl").doesNotExist());
            }
        } finally {
            // 같은 DB 를 쓰는 다른 시험(사전예약 상품을 훑는 관리자 목록)이 이 문서를 풀다 깨지지 않게 되돌린다
            jdbcTemplate.update("UPDATE products SET options = JSON_OBJECT() WHERE id = ?", (Object) UuidBinary.toBytes(productId));
        }
    }

    @Test
    @DisplayName("옵션이 없는 상품은 404 가 아니라 빈 목록이다")
    void productWithoutOptionsReturnsEmptyList() throws Exception {
        UUID productId = fixtures.product("IN_STOCK", "PAUSED");

        asUser(productId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAUSED"))
                .andExpect(jsonPath("$.data.options", hasSize(0)));
    }

    @Test
    @DisplayName("없는 상품은 404 PRODUCT_NOT_FOUND 봉투다")
    void unknownProductIsNotFound() throws Exception {
        asUser(UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    @DisplayName("없는 상품(PRODUCT_NOT_FOUND)과 틀린 경로(NOT_FOUND)는 코드로 갈린다 — 호출자가 배포 사고를 상품 없음으로 캐시하지 않게")
    void wrongPathIsPlainNotFound() throws Exception {
        UUID productId = fixtures.product("IN_STOCK", "ACTIVE");

        mockMvc.perform(get("/internal/products/{id}/optionz", productId).with(user("657").roles("USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("인증 없는 요청은 401, USER · ADMIN 은 200, 그 밖의 역할은 403")
    void requiresUserOrAdminRole() throws Exception {
        UUID productId = fixtures.product("IN_STOCK", "ACTIVE");

        mockMvc.perform(get("/internal/products/{id}/options", productId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/products/{id}/options", productId).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
        asUser(productId).andExpect(status().isOk());
        mockMvc.perform(get("/internal/products/{id}/options", productId).with(user("guest").roles("GUEST")))
                .andExpect(status().isForbidden());
    }

    private ResultActions asUser(UUID productId) throws Exception {
        return mockMvc.perform(get("/internal/products/{id}/options", productId).with(user("657").roles("USER")));
    }
}
