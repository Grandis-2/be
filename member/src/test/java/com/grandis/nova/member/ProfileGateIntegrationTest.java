package com.grandis.nova.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.common.security.JwtAuthenticationFilter;
import com.grandis.nova.common.web.RequestIdFilter;
import com.grandis.nova.member.auth.api.AuthCookies;
import com.grandis.nova.member.auth.infrastructure.kakao.KakaoOAuthClient;
import com.grandis.nova.member.auth.infrastructure.kakao.KakaoUserInfo;
import com.grandis.nova.member.support.MemberIntegrationTest;
import com.grandis.nova.member.support.MemberTestContext;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 내 정보를 다 채웠는지를 로그인·재발급·세션 조회 응답이 알려 준다(`profileComplete`).
 * 화면은 그 값이 false 면 입력을 먼저 받고, 다 채워야 넘어간다.
 *
 * 판정 기준은 "첫 로그인" 이 아니라 **미입력**이다. 첫 로그인에서 입력을 건너뛴 회원을 다음 로그인에 다시 붙잡아야 하는데,
 * 첫 로그인 기준으로는 그 회원을 영영 못 잡는다.
 *
 * 세 응답에 다 실리는 이유는 화면이 세 경로로 들어오기 때문이다 — 카카오 로그인, 새로고침(세션 조회), 액세스 만료 뒤 재발급.
 * 하나라도 빠지면 그 경로로 들어온 사용자가 입력을 건너뛴다.
 */
@MemberIntegrationTest
@DisplayName("내 정보 입력 게이트 — profileComplete")
class ProfileGateIntegrationTest {

    private static final String REDIRECT = "http://localhost:3000/login/kakao/callback";
    private static final String ORIGIN = "http://localhost:3000";
    private static final String PROFILE = "/api/v1/me/profile";
    private static final String FULL = "{\"name\":\"홍길동\",\"email\":\"hong@example.com\",\"phoneNumber\":\"010-1234-5678\"}";

    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy springSecurityFilterChain;
    @Autowired RequestIdFilter requestIdFilter;
    @MockitoBean KakaoOAuthClient kakao;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter, springSecurityFilterChain).build();
        String kakaoId = "g" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        when(kakao.exchangeCode(any(), eq(REDIRECT))).thenReturn("kakao-at");
        when(kakao.fetchUser("kakao-at")).thenReturn(new KakaoUserInfo(kakaoId, "카카오닉네임", null));
    }

    private MvcResult login() throws Exception {
        return mvc.perform(post("/api/v1/auth/kakao/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"c\",\"redirectUri\":\"" + REDIRECT + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * 응답에서 칸 하나를 꺼내되 **없으면 실패시킨다.** 빠진 칸은 `asBoolean()` 에서 false 로,
     * `asString()` 에서 빈 문자열로 나온다 — 그러면 "false 여야 한다" 는 단언이 칸이 아예 빠져도 통과한다.
     * 없음과 false 가 같은 모양으로 나오는 자리라 꺼내는 쪽에서 막는다.
     */
    private static JsonNode field(MvcResult result, String pointer) throws Exception {
        String body = result.getResponse().getContentAsString();
        JsonNode node = JSON.readTree(body).at(pointer);
        assertThat(node.isMissingNode()).describedAs("응답에 %s 가 없다: %s", pointer, body).isFalse();
        return node;
    }

    private static String accessTokenOf(MvcResult result) throws Exception {
        return field(result, "/data/sessionToken").stringValue();
    }

    private static boolean flagOf(MvcResult result) throws Exception {
        JsonNode node = field(result, "/data/profileComplete");
        assertThat(node.isBoolean()).describedAs("profileComplete 는 불리언이어야 한다: %s", node).isTrue();
        return node.booleanValue();
    }

    private void save(String token, String body) throws Exception {
        mvc.perform(put(PROFILE).header(JwtAuthenticationFilter.HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("가입 직후 로그인은 false — 카카오는 이름·이메일·연락처를 주지 않는다")
    void freshSignupIsIncomplete() throws Exception {
        MvcResult r = login();
        assertThat(flagOf(r)).isFalse();
    }

    @Test
    @DisplayName("셋을 다 채우면 그다음 로그인과 세션 조회가 true")
    void completeAfterFillingAllThree() throws Exception {
        String token = accessTokenOf(login());
        save(token, FULL);

        mvc.perform(get("/api/v1/session").header(JwtAuthenticationFilter.HEADER, token))
                .andExpect(jsonPath("$.data.profileComplete").value(true));
        assertThat(flagOf(login())).isTrue();
    }

    @Test
    @DisplayName("한 칸이라도 비면 false — 셋 중 무엇이 비든 같다")
    void anyMissingFieldKeepsItIncomplete() throws Exception {
        String token = accessTokenOf(login());
        String[][] partial = {
                {"이름 없음", "{\"email\":\"hong@example.com\",\"phoneNumber\":\"010-1234-5678\"}"},
                {"이메일 없음", "{\"name\":\"홍길동\",\"phoneNumber\":\"010-1234-5678\"}"},
                {"연락처 없음", "{\"name\":\"홍길동\",\"email\":\"hong@example.com\"}"},
        };
        for (String[] each : partial) {
            save(token, each[1]);
            mvc.perform(get("/api/v1/session").header(JwtAuthenticationFilter.HEADER, token))
                    .andExpect(jsonPath("$.data.profileComplete").value(false));
        }
    }

    @Test
    @DisplayName("다 채운 뒤 한 칸을 비우면 다시 false — 게이트는 지금 상태를 본다")
    void clearingAFieldReopensTheGate() throws Exception {
        String token = accessTokenOf(login());
        save(token, FULL);
        assertThat(flagOf(login())).isTrue();

        save(token, "{\"name\":\"홍길동\",\"email\":\"\",\"phoneNumber\":\"010-1234-5678\"}");   // 빈 문자열은 비움이다
        assertThat(flagOf(login())).isFalse();
    }

    @Test
    @DisplayName("재발급 응답에도 실린다 — 액세스가 만료돼 돌아온 화면도 같은 판정을 한다")
    void refreshCarriesTheFlag() throws Exception {
        MvcResult first = login();
        Cookie refresh = first.getResponse().getCookie(AuthCookies.REFRESH_TOKEN);
        assertThat(flagOf(first)).isFalse();

        MvcResult rotated = mvc.perform(post("/api/v1/session/refresh").header("Origin", ORIGIN).cookie(refresh))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(flagOf(rotated)).isFalse();

        save(accessTokenOf(rotated), FULL);
        MvcResult after = mvc.perform(post("/api/v1/session/refresh").header("Origin", ORIGIN)
                        .cookie(rotated.getResponse().getCookie(AuthCookies.REFRESH_TOKEN)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(flagOf(after)).isTrue();
    }

    @Test
    @DisplayName("관리자는 항상 true — 입력할 정보가 없어 막을 것도 없다")
    void adminIsNeverGated() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/admin/session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + MemberTestContext.ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String adminAccess = accessTokenOf(r);

        mvc.perform(get("/api/v1/session").header(JwtAuthenticationFilter.HEADER, adminAccess))
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.profileComplete").value(true));
    }
}
