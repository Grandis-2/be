package com.grandis.nova.preorder.api;

import com.grandis.nova.common.security.AuthRedisKeys;
import com.grandis.nova.preorder.support.AccessTokens;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.grandis.nova.preorder.support.ShopFixtures.PreorderProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static com.grandis.nova.preorder.support.AccessTokens.admin;
import static com.grandis.nova.preorder.support.AccessTokens.customer;
import static com.grandis.nova.preorder.support.AccessTokens.withToken;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 경로별 인증 · 권한. 토큰은 실제로 서명해 X-Session-Token 에 싣고 검증 필터를 그대로 통과시킨다. */
@PreorderIntegrationTest
@AutoConfigureMockMvc
class AuthenticationApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate redis;

    ShopFixtures fixtures;
    Long customerId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        customerId = fixtures.customer();
    }

    @Test
    void 토큰이_없거나_만료되거나_모르는_키로_서명되면_401() throws Exception {
        mockMvc.perform(get("/api/v1/preorders")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/preorders").with(withToken(AccessTokens.expiredCustomerToken(customerId))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/preorders").with(withToken(AccessTokens.foreignCustomerToken(customerId))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/preorders").with(customer(customerId))).andExpect(status().isOk());
    }

    @Test
    void 폐기된_회원의_토큰은_401() throws Exception {
        String token = AccessTokens.customerToken(customerId);
        long notBefore = Instant.now().plusSeconds(1).getEpochSecond();
        redis.opsForValue().set(AuthRedisKeys.notBefore(customerId.toString()), Long.toString(notBefore),
                Duration.ofMinutes(1));

        mockMvc.perform(get("/api/v1/preorders").with(withToken(token))).andExpect(status().isUnauthorized());
    }

    @Test
    void 관리자_경로는_회원_토큰이면_403_관리자_토큰이면_통과() throws Exception {
        mockMvc.perform(get("/api/v1/admin/preorders").with(customer(customerId))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/preorders").with(admin())).andExpect(status().isOk());
    }

    @Test
    void 적지_않은_경로는_토큰이_있어도_거부한다() throws Exception {
        mockMvc.perform(get("/api/v1/unknown").with(customer(customerId))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/unknown").with(admin())).andExpect(status().isForbidden());
    }

    @Test
    void 배송_차수_공개_조회와_관리_지표는_토큰_없이_열린다() throws Exception {
        PreorderProduct product = fixtures.openPreorderProduct();

        mockMvc.perform(get("/api/v1/products/{id}/shipment-batches", product.productId()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
