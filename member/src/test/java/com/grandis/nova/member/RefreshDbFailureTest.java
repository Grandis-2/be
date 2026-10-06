package com.grandis.nova.member;

import com.grandis.nova.member.support.MemberIntegrationTest;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.common.web.RequestIdFilter;
import com.grandis.nova.member.auth.api.AuthCookies;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.member.auth.application.TokenService;
import com.grandis.nova.member.customer.Customer;
import com.grandis.nova.member.customer.CustomerRepository;
import jakarta.servlet.http.Cookie;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 재발급이 회전 뒤에 회원 이름을 읽으면, 그 조회가 죽었을 때 회전만 되고 쿠키를 못 줘 다음 시도가 재사용으로 찍혔다.
 * 지금은 이름 조회가 회전 앞이다 — 조회가 죽은 첫 시도는 500 이지만 리프레시 행이 교체되지 않아 같은 쿠키로 다시 오면 200 이다.
 * CustomerRepository 를 통째로 모킹한다(JPA 프록시는 spy 로 실메서드를 못 부른다). 리프레시 행과 Redis 는 진짜다 —
 * 그래서 회원 행도 진짜로 하나 넣는다(refresh_tokens.customer_id 가 회원 표를 가리키는 외래키라 없는 회원으로는 저장이 안 된다).
 */
@MemberIntegrationTest
@DisplayName("재발급 중 DB 장애 — 회전 전에 읽으므로 같은 쿠키로 재시도가 된다")
class RefreshDbFailureTest {



    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy springSecurityFilterChain;
    @Autowired RequestIdFilter requestIdFilter;
    @Autowired TokenService tokens;
    @MockitoBean CustomerRepository customers;

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** 회원 행 하나를 진짜로 만든다. 저장소가 모킹이라 여기서 직접 넣는다. */
    private long newCustomer() {
        String kakaoId = "k-" + java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO customers(kakao_id, display_name, created_at, updated_at) VALUES (?, ?, NOW(6), NOW(6))",
                kakaoId, "홍길동");
        return jdbc.queryForObject("SELECT id FROM customers WHERE kakao_id = ?", Long.class, kakaoId);
    }

    @Test
    @DisplayName("이름 조회가 첫 재발급에서 던지면 500, 두 번째 재발급은 같은 쿠키로 200 (리프레시가 교체되지 않았다)")
    void dbFailureBeforeRotationKeepsRefreshUsable() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter, springSecurityFilterChain).build();
        long customerId = newCustomer();
        TokenService.IssuedTokens issued = tokens.issue(String.valueOf(customerId), Role.USER,
                com.grandis.nova.member.auth.application.ClientInfo.UNKNOWN);
        Cookie refresh = new Cookie(AuthCookies.REFRESH_TOKEN, issued.refreshToken());
        when(customers.findById(customerId))
                .thenThrow(new DataAccessResourceFailureException("db down"))
                .thenReturn(Optional.of(Customer.fromKakao("k", "홍길동")));

        mvc.perform(post("/api/v1/session/refresh").header("Origin", "http://localhost:3000").cookie(refresh))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
        mvc.perform(post("/api/v1/session/refresh").header("Origin", "http://localhost:3000").cookie(refresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("홍길동"));
    }
}
