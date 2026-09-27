package com.grandis.nova.member;

import com.grandis.nova.member.support.Concurrently;
import com.grandis.nova.member.support.MemberIntegrationTest;
import com.grandis.nova.member.support.MemberTestContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.common.security.JwtAuthenticationFilter;
import com.grandis.nova.common.web.RequestIdFilter;
import com.grandis.nova.member.auth.api.AuthCookies;
import com.grandis.nova.member.auth.application.KakaoLoginService;
import com.grandis.nova.member.auth.infrastructure.kakao.KakaoOAuthClient;
import com.grandis.nova.member.auth.infrastructure.kakao.KakaoUserInfo;
import com.grandis.nova.member.customer.CustomerRepository;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 첫 로그인 → customers INSERT + 토큰 / 두 번째 → INSERT 없음 / 동시 첫 로그인 2개 → 행 1개 / 재발급 / 로그아웃 후 재발급 → 401.
 * 실제 MySQL·Redis(컨테이너). 카카오만 모킹.
 * ddl-auto=validate 로 Customer 엔티티가 DDL 과 맞는지 컨텍스트 기동에서 확인된다.
 */
@MemberIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("member 통합 (실제 MySQL·Redis, 카카오 모킹)")
class AuthFlowIntegrationTest {

    static final String ADMIN_PASSWORD = MemberTestContext.ADMIN_PASSWORD;
    static final String REDIRECT = "http://localhost:3000/login/kakao/callback";
    static final String ORIGIN = "http://localhost:3000";



    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy springSecurityFilterChain;
    @Autowired RequestIdFilter requestIdFilter;
    @Autowired CustomerRepository customers;
    @Autowired KakaoLoginService loginService;
    @MockitoBean KakaoOAuthClient kakao;

    private MockMvc mvc;
    private String kakaoId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestIdFilter, springSecurityFilterChain).build();
        kakaoId = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);   // 회원 행은 시험마다 새로. 지우지 않는다(로컬 일회용 DB)
        when(kakao.exchangeCode(any(), eq(REDIRECT))).thenReturn("kakao-at");
        when(kakao.fetchUser("kakao-at")).thenReturn(new KakaoUserInfo(kakaoId, "홍길동", null));
    }

    private MvcResult login() throws Exception {
        return mvc.perform(post("/api/v1/auth/kakao/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"c\",\"redirectUri\":\"" + REDIRECT + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static String json(MvcResult r, String field) throws Exception {
        String body = r.getResponse().getContentAsString();
        return new tools.jackson.databind.ObjectMapper().readTree(body).at(field).asString();
    }

    private static Cookie refreshCookie(MvcResult r) {
        return r.getResponse().getCookie(AuthCookies.REFRESH_TOKEN);
    }

    @Test
    @DisplayName("첫 로그인: customers 에 행이 생기고 sessionToken·displayName·role 과 refresh 쿠키가 온다. 내부 PK·kakao_id 는 없다")
    void firstLoginCreatesCustomerAndIssuesTokens() throws Exception {
        assertThat(customers.findByKakaoId(kakaoId)).isEmpty();

        MvcResult r = login();

        assertThat(customers.findByKakaoId(kakaoId)).isPresent();
        String body = r.getResponse().getContentAsString();
        assertThat(json(r, "/success")).isEqualTo("true");
        assertThat(json(r, "/data/sessionToken")).isNotBlank();
        assertThat(json(r, "/data/displayName")).isEqualTo("홍길동");
        assertThat(json(r, "/data/role")).isEqualTo("USER");
        assertThat(json(r, "/timestamp")).endsWith("Z");                  // api-spec: ISO8601 UTC
        assertThat(body).contains("\"error\":null");                       // 봉투는 명시적 null 이 계약. Jackson 3 기본 inclusion=ALWAYS (실측)
        assertThat(body).doesNotContain(kakaoId).doesNotContain("customerId").doesNotContain("\"id\"");
        Cookie c = refreshCookie(r);
        assertThat(c).isNotNull();
        assertThat(c.isHttpOnly()).isTrue();
        assertThat(c.getPath()).isEqualTo(AuthCookies.PATH);
        assertThat(c.getMaxAge()).isEqualTo(14 * 24 * 3600);
        // 쿠키 속성 다섯 중 나머지 둘. 이 컨텍스트는 auth.cookie.secure=false 라 Secure 가 없어야 한다(true 쪽은 ConfigBindingTest)
        String setCookie = r.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains("SameSite=Strict").doesNotContainIgnoringCase("secure");
    }

    @Test
    @DisplayName("두 번째 로그인: INSERT 없이 같은 행, 새 sid")
    void secondLoginReusesRow() throws Exception {
        login();
        long before = customers.count();
        Long id = customers.findByKakaoId(kakaoId).orElseThrow().getId();

        login();

        assertThat(customers.count()).isEqualTo(before);
        assertThat(customers.findByKakaoId(kakaoId).orElseThrow().getId()).isEqualTo(id);
    }

    @Test
    @DisplayName("실측: 같은 카카오 회원의 동시 첫 로그인 8개 → 행 1개, 전부 성공, 그리고 1062 재조회 분기에 실제로 들어갔다(로그 횟수 ≥ 1)")
    void concurrentFirstLoginCreatesOneRow(CapturedOutput output) throws Exception {
        int n = 8;
        // 카카오 조회를 흉내 내는 자리에서 n 개를 모아 같이 출발시킨다 — 조회(없음)→INSERT 창에 여럿이 같이 들어가게
        CyclicBarrier gate = new CyclicBarrier(n);
        when(kakao.fetchUser("kakao-at")).thenAnswer(inv -> {
            gate.await(10, TimeUnit.SECONDS);
            return new KakaoUserInfo(kakaoId, "홍길동", null);
        });
        java.util.List<Concurrently.Outcome<KakaoLoginService.LoginResult>> results = Concurrently.run(n,
                i -> () -> loginService.login("c", REDIRECT, com.grandis.nova.member.auth.application.ClientInfo.UNKNOWN));

        assertThat(results).allSatisfy(r -> {
            assertThat(r.error()).isNull();
            assertThat(r.value().displayName()).isEqualTo("홍길동");
        });
        assertThat(customers.findAll().stream().filter(c -> c.getKakaoId().equals(kakaoId)).count()).isEqualTo(1);
        // catch (DataIntegrityViolationException) 에 들어간 횟수. 0 이면 직렬화돼서 분기를 안 밟은 것이고 이 시험은 실측이 아니다
        long lostRaces = output.getOut().lines().filter(l -> l.contains("customers insert lost the race on kakao_id")).count();
        assertThat(lostRaces).as("1062 재조회 분기 진입 횟수").isBetween(1L, (long) n - 1);
    }

    @Test
    @DisplayName("GET /session: 액세스 토큰으로 displayName·role. 토큰 없으면 401 봉투")
    void sessionEndpoint() throws Exception {
        MvcResult r = login();
        String access = json(r, "/data/sessionToken");

        mvc.perform(get("/api/v1/session").header(JwtAuthenticationFilter.HEADER, access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("홍길동"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.profileComplete").value(false))   // 가입 직후라 아직 안 채웠다
                .andExpect(jsonPath("$.data.sessionToken").doesNotExist());   // GET /session 에 토큰은 없다
        mvc.perform(get("/api/v1/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("재발급: 쿠키만으로 200, 새 sessionToken 과 새 refresh 쿠키. 옛 리프레시를 다시 내면 재사용 탐지로 401")
    void refreshRotatesAndDetectsReuse() throws Exception {
        MvcResult r = login();
        Cookie first = refreshCookie(r);

        MvcResult rotated = mvc.perform(post("/api/v1/session/refresh").header("Origin", ORIGIN).cookie(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionToken").isNotEmpty())
                .andExpect(jsonPath("$.data.displayName").value("홍길동"))
                .andExpect(cookie().exists(AuthCookies.REFRESH_TOKEN))
                .andReturn();
        assertThat(refreshCookie(rotated).getValue()).isNotEqualTo(first.getValue());

        mvc.perform(post("/api/v1/session/refresh").header("Origin", ORIGIN).cookie(first))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        // 재사용 탐지는 세션을 통째로 끊는다: 새 리프레시도 죽는다
        mvc.perform(post("/api/v1/session/refresh").header("Origin", ORIGIN).cookie(refreshCookie(rotated)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("JWKS: GET /.well-known/jwks.json 은 공개이고, 그 문서만으로 만든 검증 전용 provider 가 로그인 토큰을 검증한다 — 다른 서비스가 받을 계약")
    void jwksEndpointServesVerifiableKeys() throws Exception {
        MvcResult r = login();
        String access = json(r, "/data/sessionToken");
        String jwks = mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kid").value(TestKeys.KID))
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andReturn().getResponse().getContentAsString();

        var publicKeys = com.grandis.nova.common.security.JwtKeyRing.parseJwks(jwks);
        java.util.Map<String, String> pems = new java.util.LinkedHashMap<>();
        publicKeys.forEach((kid, key) -> pems.put(kid, com.grandis.nova.common.security.PemKeys.toPem(key)));
        var verifierProps = new com.grandis.nova.common.security.JwtProperties("nova-test", java.time.Duration.ofHours(1), java.time.Duration.ofDays(14), null, null, null, pems, null, null);
        var verifier = new com.grandis.nova.common.security.JwtTokenProvider(verifierProps,
                new com.grandis.nova.common.security.JwtKeyRing(verifierProps, java.time.Clock.systemUTC(), org.springframework.web.client.RestClient.create(), Runnable::run), java.time.Clock.systemUTC());
        assertThat(verifier.parse(access).role()).isEqualTo(com.grandis.nova.common.security.Role.USER);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> verifier.create("1", com.grandis.nova.common.security.Role.USER, UUID.randomUUID(), com.grandis.nova.common.security.TokenType.ACCESS))
                .isInstanceOf(IllegalStateException.class);   // 검증 서비스는 발급 못 한다
    }

    @Test
    @DisplayName("재발급은 Origin 이 없거나 허용 목록 밖이면 403 FORBIDDEN 이고 회전이 안 된다(같은 쿠키로 다시 오면 200)")
    void refreshRequiresAllowedOrigin() throws Exception {
        MvcResult r = login();
        Cookie first = refreshCookie(r);

        mvc.perform(post("/api/v1/session/refresh").cookie(first))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mvc.perform(post("/api/v1/session/refresh").header("Origin", "https://evil.example").cookie(first))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/session/refresh").header("Origin", ORIGIN).cookie(first))
                .andExpect(status().isOk());   // 거절된 두 번은 회전을 안 했다
        // 실측: 잘못된 퍼센트 시퀀스 쿠키는 @CookieValue 디코딩에서 500 이 났었다. 원문으로 읽으니 401
        mvc.perform(post("/api/v1/session/refresh").header("Origin", ORIGIN).cookie(new Cookie(AuthCookies.REFRESH_TOKEN, "%%%")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("관리자 리프레시는 admin_refresh_token 쿠키로 오고, 그것만으로 재발급되며 회원 쿠키와 이름이 다르다")
    void adminRefreshCookieIsSeparate() throws Exception {
        MvcResult ok = mvc.perform(post("/api/v1/admin/session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists(AuthCookies.ADMIN_REFRESH_TOKEN))
                .andExpect(cookie().doesNotExist(AuthCookies.REFRESH_TOKEN))
                .andReturn();
        Cookie adminCookie = ok.getResponse().getCookie(AuthCookies.ADMIN_REFRESH_TOKEN);

        mvc.perform(post("/api/v1/session/refresh").header("Origin", ORIGIN).cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.displayName").value(org.hamcrest.Matchers.nullValue()))
                // 관리자는 회원 행이 없어 입력할 정보도 없다. 재발급 응답에도 그 값이 실린다 — 화면이 이 경로로도 들어온다.
                .andExpect(jsonPath("$.data.profileComplete").value(true))
                .andExpect(cookie().exists(AuthCookies.ADMIN_REFRESH_TOKEN));
    }

    @Test
    @DisplayName("관리자 리프레시도 한 번만 쓴다: 교체된 쿠키를 다시 내면 401, 세션이 끊겨 새 쿠키도 죽는다")
    void adminRefreshCookieIsSingleUse() throws Exception {
        MvcResult login = mvc.perform(post("/api/v1/admin/session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie first = login.getResponse().getCookie(AuthCookies.ADMIN_REFRESH_TOKEN);

        MvcResult rotated = mvc.perform(post("/api/v1/session/refresh").header("Origin", ORIGIN).cookie(first))
                .andExpect(status().isOk())
                .andReturn();
        Cookie second = rotated.getResponse().getCookie(AuthCookies.ADMIN_REFRESH_TOKEN);
        assertThat(second.getValue()).isNotEqualTo(first.getValue());

        // 옛 쿠키 재사용 → 401. 회원 쪽과 같은 규칙인데 저장소가 다르다(관리자는 Redis 비교교환, 회원은 DB 행 잠금).
        mvc.perform(post("/api/v1/session/refresh").header("Origin", ORIGIN).cookie(first))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        // 재사용을 보면 세션을 끊는다 — 방금 받은 새 쿠키도 같이 죽는다.
        // 상태만 보면 안 된다: 401 은 다른 사유로도 난다. 봉투의 코드까지 봐야 "폐기돼서" 임이 고정된다.
        mvc.perform(post("/api/v1/session/refresh").header("Origin", ORIGIN).cookie(second))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("로그아웃은 공개다: 액세스가 깨졌어도(만료와 같은 처리) 리프레시 쿠키의 sid 를 끊고 204 + 두 쿠키 만료. 그 뒤 재발급 401")
    void logoutWithBrokenAccessStillRevokesViaRefreshCookie() throws Exception {
        MvcResult r = login();
        Cookie refresh = refreshCookie(r);

        mvc.perform(delete("/api/v1/session").header(JwtAuthenticationFilter.HEADER, "not-a-jwt").cookie(refresh))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(AuthCookies.REFRESH_TOKEN, 0))
                .andExpect(cookie().maxAge(AuthCookies.ADMIN_REFRESH_TOKEN, 0));

        mvc.perform(post("/api/v1/session/refresh").header("Origin", ORIGIN).cookie(refresh)).andExpect(status().isUnauthorized());
        // 아무것도 없이 쳐도, 헤더·쿠키 셋 다 의미 없는 문자열이어도 204 — 로그아웃은 실패하지 않는다(500 이 아니다)
        mvc.perform(delete("/api/v1/session")).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/session").header(JwtAuthenticationFilter.HEADER, "garbage")
                        .cookie(new Cookie(AuthCookies.REFRESH_TOKEN, "garbage"), new Cookie(AuthCookies.ADMIN_REFRESH_TOKEN, "%%%")))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(AuthCookies.REFRESH_TOKEN, 0));
    }

    @Test
    @DisplayName("로그아웃 뒤: 재발급 401, 그 액세스 토큰도 401 (sid 폐기 표식), 쿠키는 만료로 내려온다")
    void logoutRevokesSession() throws Exception {
        MvcResult r = login();
        String access = json(r, "/data/sessionToken");
        Cookie refresh = refreshCookie(r);

        mvc.perform(delete("/api/v1/session").header(JwtAuthenticationFilter.HEADER, access))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(AuthCookies.REFRESH_TOKEN, 0));

        mvc.perform(post("/api/v1/session/refresh").header("Origin", ORIGIN).cookie(refresh)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/session").header(JwtAuthenticationFilter.HEADER, access)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("관리자 로그인: 맞으면 role ADMIN 토큰 + 쿠키(data 는 sessionToken·role 둘), 그 토큰으로 GET /session 이 ADMIN. 틀리면 401 INVALID_CREDENTIALS")
    void adminLogin() throws Exception {
        MvcResult ok = mvc.perform(post("/api/v1/admin/session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.sessionToken").isNotEmpty())
                .andExpect(jsonPath("$.data.displayName").doesNotExist())   // api-spec POST /admin/session data 는 {sessionToken, role} 둘
                .andReturn();
        String admin = json(ok, "/data/sessionToken");
        mvc.perform(get("/api/v1/session").header(JwtAuthenticationFilter.HEADER, admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.role").value("ADMIN"));

        mvc.perform(post("/api/v1/admin/session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/api/v1/admin/session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"root\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("카카오가 거절하면(허용 목록 밖 redirectUri) 400 INVALID_OAUTH_CALLBACK 봉투이고 회원은 안 생긴다")
    void kakaoFailureIs400() throws Exception {
        when(kakao.exchangeCode(any(), eq("https://evil.example/cb")))
                .thenThrow(new com.grandis.nova.common.BusinessException(com.grandis.nova.member.auth.AuthErrorCode.INVALID_OAUTH_CALLBACK));

        mvc.perform(post("/api/v1/auth/kakao/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"c\",\"redirectUri\":\"https://evil.example/cb\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_OAUTH_CALLBACK"))
                .andExpect(header().exists(RequestIdFilter.HEADER));
        assertThat(customers.findByKakaoId(kakaoId)).isEmpty();
    }
}
