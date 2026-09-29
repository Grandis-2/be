package com.grandis.nova.catalog.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 폐기 조회가 실패하면(Redis 장애) 닫는 경로만 401 이고 나머지는 통과한다(D-2). catalog 는 공통 기본 목록을 쓰므로
 * 관리자 경로가 닫히고 내부 조회 · 공개 조회는 열린다. 체커를 죽은 것으로 모킹해 그 갈래를 만든다.
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
        fixtures.completeRegistration(productId);
        when(checker.isRevoked(any())).thenThrow(new RevocationCheckFailedException(new RuntimeException("redis down")));
    }

    @Test
    @DisplayName("폐기 조회 실패 — 관리자 경로는 401 retryable(닫는 경로), 내부 조회 · 관리자 미리보기는 열린 채 통과")
    void adminClosedOthersOpen() throws Exception {
        mockMvc.perform(get("/api/v1/admin/products").with(AccessTokens.admin()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.error.details.retryable").value(true));
        mockMvc.perform(get("/internal/products/{id}/options", productId).with(AccessTokens.customer(657L))).andExpect(status().isOk());
        jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = ?", productId);
        mockMvc.perform(get("/api/v1/products/{id}", productId).with(AccessTokens.admin())).andExpect(status().isOk());
    }
}
