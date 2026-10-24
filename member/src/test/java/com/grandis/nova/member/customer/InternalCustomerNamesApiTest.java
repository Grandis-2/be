package com.grandis.nova.member.customer;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.common.web.RequestIdFilter;
import com.grandis.nova.member.support.Containers;
import com.grandis.nova.member.support.MemberIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@MemberIntegrationTest
@DisplayName("내부 조회 GET /internal/customers/names — order · preorder 관리자 화면의 회원 이름")
class InternalCustomerNamesApiTest {

    private static final String PATH = "/internal/customers/names";

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
    @DisplayName("관리자 API 와 같은 응답 — 요청 순서로 가린 이름, 같은 id 는 한 번, 없는 회원은 빠진다")
    void returnsMaskedNamesLikeTheAdminApi() throws Exception {
        Customer withName = customers.saveAndFlush(Customer.fromKakao(kakaoId(), "철수랑"));
        withName.changeProfile(new Profile("김철수", null, null));
        customers.saveAndFlush(withName);
        Customer nicknameOnly = customers.saveAndFlush(Customer.fromKakao(kakaoId(), "영희랑"));

        mvc.perform(get(PATH).header(BearerTokens.HEADER, admin())
                        .param("ids", nicknameOnly.getId().toString(), UUID.randomUUID().toString(),
                                withName.getId().toString(), nicknameOnly.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].memberId").value(nicknameOnly.getId().toString()))
                .andExpect(jsonPath("$.data.items[0].name").value("영**"))
                .andExpect(jsonPath("$.data.items[1].memberId").value(withName.getId().toString()))
                .andExpect(jsonPath("$.data.items[1].name").value("김**"));
    }

    @Test
    @DisplayName("관리자 토큰만 — 익명 401, 회원 토큰 403(남의 이름을 물을 길을 만들지 않는다). HEAD 도 GET 핸들러로 가므로 같다")
    void onlyAdminTokens() throws Exception {
        Customer customer = customers.saveAndFlush(Customer.fromKakao(kakaoId(), "철수랑"));
        String ids = customer.getId().toString();
        String user = BearerTokens.value(token(customer.getId().toString(), Role.USER));
        mvc.perform(get(PATH).param("ids", ids)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).param("ids", ids).header(BearerTokens.HEADER, user)).andExpect(status().isForbidden());
        mvc.perform(head(PATH).param("ids", ids).header(BearerTokens.HEADER, user)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ids 는 1~100개 — 관리자 목록 한 쪽(최대 100줄)이 한 번에 든다. 0개 · 101개는 400")
    void acceptsOneToOneHundredIds() throws Exception {
        mvc.perform(get(PATH).header(BearerTokens.HEADER, admin()).param("ids", ids(100)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get(PATH).header(BearerTokens.HEADER, admin()).param("ids", ids(101)))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PATH).header(BearerTokens.HEADER, admin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("문장 하나 — 읽기 전용 트랜잭션 없이 id · 실명 · 표시명만 읽는다(연락처 · 배송지 칸은 읽지 않는다)")
    void readsNamesInOneStatement() throws Exception {
        Customer customer = customers.saveAndFlush(Customer.fromKakao(kakaoId(), "철수랑"));

        List<String> statements = new ArrayList<>();
        try (Connection root = DriverManager.getConnection(Containers.MYSQL.getJdbcUrl(), "root", Containers.MYSQL.getPassword());
             Statement statement = root.createStatement()) {
            statement.execute("SET GLOBAL log_output = 'TABLE'");
            statement.execute("SET GLOBAL general_log = 'ON'");
            statement.execute("TRUNCATE TABLE mysql.general_log");
            try {
                mvc.perform(get(PATH).header(BearerTokens.HEADER, admin()).param("ids", customer.getId().toString()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.items[0].name").value("철**"));
            } finally {
                statement.execute("SET GLOBAL general_log = 'OFF'");
            }
            // 이름을 읽은 연결의 문장만 — 다른 연결의 문장을 섞지 않는다
            try (ResultSet rs = statement.executeQuery("SELECT argument FROM mysql.general_log WHERE command_type = 'Query' AND thread_id IN"
                    + " (SELECT thread_id FROM mysql.general_log WHERE argument LIKE '%from customers c1_0 where c1_0.id in%')"
                    + " ORDER BY event_time")) {
                while (rs.next()) {
                    statements.add(new String(rs.getBytes(1), StandardCharsets.UTF_8));
                }
            }
        }

        assertThat(statements).as("이름 조회 문장 하나뿐 — 트랜잭션 준비 · 정리 문장이 없다").hasSize(1);
        assertThat(statements.getFirst()).doesNotContain("phone_number", "email", "default_ship_to");
    }

    private String admin() {
        return BearerTokens.value(token("admin", Role.ADMIN));
    }

    private String token(String subject, Role role) {
        return provider.create(subject, role, UUID.randomUUID(), TokenType.ACCESS);
    }

    private static String[] ids(int count) {
        return IntStream.range(0, count).mapToObj(i -> UUID.randomUUID().toString()).toArray(String[]::new);
    }

    private static String kakaoId() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
    }
}
