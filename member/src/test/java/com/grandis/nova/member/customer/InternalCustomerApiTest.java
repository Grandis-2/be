package com.grandis.nova.member.customer;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.common.web.RequestIdFilter;
import com.grandis.nova.member.support.MemberIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@MemberIntegrationTest
@DisplayName("내부 조회 GET /internal/customers/me — 토큰 주인의 표시명(catalog 리뷰 작성자명)")
class InternalCustomerApiTest {

    private static final String PATH = "/internal/customers/me";

    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy springSecurityFilterChain;
    @Autowired RequestIdFilter requestIdFilter;
    @Autowired JwtTokenProvider provider;
    @Autowired CustomerRepository customers;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter, springSecurityFilterChain).build();
    }

    @Test
    @DisplayName("회원 토큰이면 그 회원의 id · 표시명")
    void returnsTheTokenOwnersDisplayName() throws Exception {
        Customer customer = customers.saveAndFlush(Customer.fromKakao(kakaoId(), "김철수"));
        mvc.perform(get(PATH).header(BearerTokens.HEADER, BearerTokens.value(token(customer.getId().toString(), Role.USER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value(customer.getId()))
                .andExpect(jsonPath("$.data.displayName").value("김철수"));
    }

    @Test
    @DisplayName("익명 401, 관리자 403, 토큰의 회원이 없으면 401")
    void accessRules() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header(BearerTokens.HEADER, BearerTokens.value(token("admin", Role.ADMIN))))
                .andExpect(status().isForbidden());
        mvc.perform(get(PATH).header(BearerTokens.HEADER, BearerTokens.value(token("999999999", Role.USER))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    private String token(String subject, Role role) {
        return provider.create(subject, role, UUID.randomUUID(), TokenType.ACCESS);
    }

    private static String kakaoId() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
    }
}
