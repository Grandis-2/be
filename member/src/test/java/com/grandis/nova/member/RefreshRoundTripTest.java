package com.grandis.nova.member;

import com.grandis.nova.common.web.RequestIdFilter;
import com.grandis.nova.member.auth.api.AuthCookies;
import com.grandis.nova.member.auth.infrastructure.kakao.KakaoOAuthClient;
import com.grandis.nova.member.auth.infrastructure.kakao.KakaoUserInfo;
import com.grandis.nova.member.support.Containers;
import com.grandis.nova.member.support.MemberIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 재발급 한 번이 DB 로 보내는 문장. 운영 DB 왕복이 수십 ms 라 같은 행을 두 번 읽거나 단건 조회를 읽기 전용 트랜잭션으로 감싸면
 * 그 준비 · 정리 문장이 쿼리보다 비싸다. 회전(잠금 · 재사용 판정 · 새 행)은 한 트랜잭션 그대로다.
 */
@MemberIntegrationTest
@DisplayName("재발급 DB 왕복 (실제 MySQL · Redis, 카카오 모킹)")
class RefreshRoundTripTest {

    static final String REDIRECT = "http://localhost:3000/login/kakao/callback";
    static final String ORIGIN = "http://localhost:3000";

    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy springSecurityFilterChain;
    @Autowired RequestIdFilter requestIdFilter;
    @MockitoBean KakaoOAuthClient kakao;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter, springSecurityFilterChain).build();
        String kakaoId = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        when(kakao.exchangeCode(any(), eq(REDIRECT))).thenReturn("kakao-at");
        when(kakao.fetchUser("kakao-at")).thenReturn(new KakaoUserInfo(kakaoId, "홍길동", null));
    }

    @Test
    @DisplayName("회원 재발급은 토큰 행을 트랜잭션 없이 한 번 읽고 회전에서 그 행을 다시 찾지 않으며, 회원 이름은 한 문장으로 읽는다 — 26 → 10문장")
    void userRefreshSendsTenStatements() throws Exception {
        Cookie cookie = mvc.perform(post("/api/v1/auth/kakao/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"c\",\"redirectUri\":\"" + REDIRECT + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie(AuthCookies.REFRESH_TOKEN);

        List<String> statements = new ArrayList<>();
        try (Connection root = DriverManager.getConnection(Containers.MYSQL.getJdbcUrl(), "root", Containers.MYSQL.getPassword());
             Statement statement = root.createStatement()) {
            statement.execute("SET GLOBAL log_output = 'TABLE'");
            statement.execute("SET GLOBAL general_log = 'ON'");
            statement.execute("TRUNCATE TABLE mysql.general_log");
            try {
                mvc.perform(post("/api/v1/session/refresh").header("Origin", ORIGIN).cookie(cookie))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.displayName").value("홍길동"));
            } finally {
                statement.execute("SET GLOBAL general_log = 'OFF'");
            }
            // 토큰 행을 읽은 연결의 문장만 — 같은 서버를 쓰는 다른 작업(정리 배치 등)의 문장을 섞지 않는다
            try (ResultSet rs = statement.executeQuery("SELECT argument FROM mysql.general_log WHERE command_type = 'Query' AND thread_id IN"
                    + " (SELECT thread_id FROM mysql.general_log WHERE argument LIKE '%from refresh_tokens rt1_0 where rt1_0.token_hash%')"
                    + " ORDER BY event_time")) {
                while (rs.next()) {
                    statements.add(new String(rs.getBytes(1), StandardCharsets.UTF_8));
                }
            }
        }

        assertThat(statements).as("읽기 전용 트랜잭션 없음").noneMatch(sql -> sql.contains("READ ONLY"));
        assertThat(statements).as("토큰 행 전체 읽기 — 주인 찾기 한 번 + 회전의 잠금 읽기 한 번")
                .filteredOn(sql -> sql.startsWith("select rt1_0.id,") && sql.contains("from refresh_tokens rt1_0")).hasSize(2);
        assertThat(statements).as("회원 이름 한 문장").filteredOn(sql -> sql.contains("from customers c1_0 where c1_0.id")).hasSize(1);
        assertThat(statements).as("주인 찾기 · 회원 이름 2 + 회전 트랜잭션 8").hasSize(10);
    }
}
