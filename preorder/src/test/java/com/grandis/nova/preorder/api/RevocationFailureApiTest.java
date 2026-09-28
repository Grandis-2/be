package com.grandis.nova.preorder.api;

import com.grandis.nova.common.security.RevocationCheckFailedException;
import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.grandis.nova.preorder.support.AccessTokens.admin;
import static com.grandis.nova.preorder.support.AccessTokens.customer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 폐기 확인(Redis)이 안 될 때: 되돌릴 수 없는 취소와 관리자 기능만 막고, 조회 · 접수 흐름은 통과시킨다. */
@PreorderIntegrationTest
@AutoConfigureMockMvc
class RevocationFailureApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    RevocationChecker revocationChecker;

    Long customerId;

    @BeforeEach
    void setUp() {
        customerId = new ShopFixtures(jdbcTemplate).customer();
        given(revocationChecker.isRevoked(any()))
                .willThrow(new RevocationCheckFailedException(new QueryTimeoutException("redis down")));
    }

    @Test
    void 예약_취소와_관리자_경로는_401() throws Exception {
        mockMvc.perform(post("/api/v1/preorders/{id}/cancel", ShopFixtures.unique()).with(customer(customerId)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/preorders").with(admin())).andExpect(status().isUnauthorized());
    }

    @Test
    void 내_예약_조회는_통과한다() throws Exception {
        mockMvc.perform(get("/api/v1/preorders").with(customer(customerId))).andExpect(status().isOk());
    }
}
