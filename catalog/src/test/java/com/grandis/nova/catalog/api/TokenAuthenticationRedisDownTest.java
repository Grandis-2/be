package com.grandis.nova.catalog.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.catalog.support.AccessTokens;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.security.RevocationCheckFailedException;
import com.grandis.nova.common.security.RevocationChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 폐기 조회가 실패하면(Redis 장애) 닫는 경로만 401 이고 나머지는 통과한다. catalog 는 공통 기본 목록에 리뷰 쓰기를 더한 목록을 쓰므로
 * 관리자 경로 · 리뷰 작성 · 수정 · 삭제가 닫히고 내부 조회 · 공개 조회 · 리뷰 조회는 열린다. 체커를 죽은 것으로 모킹해 그 갈래를 만든다.
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
class TokenAuthenticationRedisDownTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean RevocationChecker checker;

    Long productId;

    @BeforeEach
    void setUp() {
        ShopFixtures fixtures = new ShopFixtures(jdbcTemplate);
        productId = fixtures.product("IN_STOCK", "ACTIVE");
        fixtures.registration(productId);
        fixtures.option(productId, "ACTIVE");
        fixtures.stockReady(productId);   // 공개 상세가 200 이려면 판매 방식별 준비(재고 행)가 있어야 한다
        when(checker.isRevoked(any())).thenThrow(new RevocationCheckFailedException(new RuntimeException("redis down")));
    }

    @Test
    @DisplayName("폐기 조회 실패 — 관리자 경로는 401 retryable(닫는 경로), 내부 조회 · 공개 상세는 열린 채 통과")
    void adminClosedOthersOpen() throws Exception {
        mockMvc.perform(get("/api/v1/admin/products").with(AccessTokens.admin()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.details.retryable").value(true));
        mockMvc.perform(get("/internal/products/{id}/options", productId).with(AccessTokens.customer(657L))).andExpect(status().isOk());
        // 공개 상세는 토큰과 무관하게 열린다 — 비공개면 관리자 토큰으로도 404(미리보기 없음)
        mockMvc.perform(get("/api/v1/products/{id}", productId).with(AccessTokens.admin())).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = ?", productId);
        mockMvc.perform(get("/api/v1/products/{id}", productId).with(AccessTokens.admin())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("폐기 조회 실패 — 리뷰 작성 · 수정 · 삭제는 401 retryable(닫는 경로), 상품 리뷰 · 모아보기 · 내 리뷰 조회는 열린 채 통과")
    void reviewWritesClosedReadsOpen() throws Exception {
        String body = "{ \"orderItemId\": 1, \"rating\": 5, \"body\": \"x\" }";
        expectClosed(mockMvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(AccessTokens.customer(657L))));
        expectClosed(mockMvc.perform(patch("/api/v1/reviews/1").contentType(MediaType.APPLICATION_JSON).content("{ \"rating\": 1 }")
                .with(AccessTokens.customer(657L))));
        expectClosed(mockMvc.perform(delete("/api/v1/reviews/1").with(AccessTokens.customer(657L))));
        mockMvc.perform(get("/api/v1/products/{id}/reviews", productId).with(AccessTokens.customer(657L))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/reviews").with(AccessTokens.customer(657L))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/reviews/mine").with(AccessTokens.customer(657L))).andExpect(status().isOk());
    }

    private static void expectClosed(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        actions.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.details.retryable").value(true));
    }
}
