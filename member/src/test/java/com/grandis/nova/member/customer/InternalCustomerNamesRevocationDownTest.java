package com.grandis.nova.member.customer;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.RevocationCheckFailedException;
import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.common.web.RequestIdFilter;
import com.grandis.nova.member.support.MemberIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 폐기 조회(Redis)가 죽으면 관리자 경로처럼 닫는다 — 폐기된 관리자 토큰으로 member 내부 주소를 바로 불러 이름을 받을 수 없게.
 * 부르는 쪽(order · preorder 의 /api/v1/admin/**)도 닫지만, 내부 주소로 바로 오는 요청은 그 검사를 거치지 않는다.
 */
@MemberIntegrationTest
@DisplayName("GET /internal/customers/names — 폐기 조회가 죽으면 401(관리자 경로와 같이 닫는다)")
class InternalCustomerNamesRevocationDownTest {

    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy springSecurityFilterChain;
    @Autowired RequestIdFilter requestIdFilter;
    @Autowired JwtTokenProvider provider;
    @MockitoBean RevocationChecker checker;

    @Test
    @DisplayName("관리자 토큰이어도 폐기 여부를 모르면 401 — GET · HEAD 모두")
    void closesWhenRevocationCheckFails() throws Exception {
        doThrow(new RevocationCheckFailedException(new RedisConnectionFailureException("down"))).when(checker).isRevoked(any());
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter, springSecurityFilterChain).build();
        String admin = BearerTokens.value(provider.create("admin", Role.ADMIN, UUID.randomUUID(), TokenType.ACCESS));
        String ids = UUID.randomUUID().toString();

        mvc.perform(get("/internal/customers/names").param("ids", ids).header(BearerTokens.HEADER, admin))
                .andExpect(status().isUnauthorized());
        mvc.perform(head("/internal/customers/names").param("ids", ids).header(BearerTokens.HEADER, admin))
                .andExpect(status().isUnauthorized());
    }
}
