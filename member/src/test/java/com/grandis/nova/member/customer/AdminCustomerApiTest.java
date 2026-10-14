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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@MemberIntegrationTest
@DisplayName("관리자 회원 이름 조회 GET /api/v1/admin/members?ids= — 관리자 예약 목록의 회원 id 를 이름으로")
class AdminCustomerApiTest {

    private static final String PATH = "/api/v1/admin/members";

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
    @DisplayName("요청 순서로 이름 — 실명이 있으면 실명, 없으면 카카오 표시 이름. 같은 id 는 한 번, 없는 회원은 빠진다")
    void returnsNamesInRequestOrder() throws Exception {
        Customer withName = customers.saveAndFlush(Customer.fromKakao(kakaoId(), "철수랑"));
        withName.changeProfile(new Profile("김철수", null, null));
        customers.saveAndFlush(withName);
        Customer nicknameOnly = customers.saveAndFlush(Customer.fromKakao(kakaoId(), "영희랑"));
        UUID missing = UUID.randomUUID();

        mvc.perform(asAdmin(get(PATH).param("ids", nicknameOnly.getId() + "," + missing + "," + withName.getId() + "," + nicknameOnly.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].memberId").value(nicknameOnly.getId().toString()))
                .andExpect(jsonPath("$.data.items[0].name").value("영희랑"))
                .andExpect(jsonPath("$.data.items[1].memberId").value(withName.getId().toString()))
                .andExpect(jsonPath("$.data.items[1].name").value("김철수"));
        // 반복 파라미터도 같다
        mvc.perform(asAdmin(get(PATH).param("ids", withName.getId().toString()).param("ids", nicknameOnly.getId().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].name").value("김철수"))
                .andExpect(jsonPath("$.data.items[1].name").value("영희랑"));
    }

    @Test
    @DisplayName("ids 는 1~100개 — 100 은 200, 101 · 없음 · 빈 원소 · UUID 아님은 ids 칸의 400")
    void idsMustBeOneToHundred() throws Exception {
        String hundred = IntStream.range(0, 100).mapToObj(i -> UUID.randomUUID().toString()).collect(Collectors.joining(","));
        mvc.perform(asAdmin(get(PATH).param("ids", hundred))).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        expectIdsViolation(mvc.perform(asAdmin(get(PATH).param("ids", hundred + "," + UUID.randomUUID()))));
        expectIdsViolation(mvc.perform(asAdmin(get(PATH))));
        expectIdsViolation(mvc.perform(asAdmin(get(PATH).param("ids", UUID.randomUUID() + ","))));
        expectIdsViolation(mvc.perform(asAdmin(get(PATH).param("ids", "not-a-uuid"))));
    }

    @Test
    @DisplayName("관리자만 — 익명 401, 회원 403")
    void adminOnly() throws Exception {
        String ids = UUID.randomUUID().toString();
        mvc.perform(get(PATH).param("ids", ids)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).param("ids", ids).header(BearerTokens.HEADER, BearerTokens.value(token(UUID.randomUUID().toString(), Role.USER))))
                .andExpect(status().isForbidden());
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
        return builder.header(BearerTokens.HEADER, BearerTokens.value(token("admin", Role.ADMIN)));
    }

    private static void expectIdsViolation(ResultActions actions) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("ids"));
    }

    private String token(String subject, Role role) {
        return provider.create(subject, role, UUID.randomUUID(), TokenType.ACCESS);
    }

    private static String kakaoId() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
    }
}
