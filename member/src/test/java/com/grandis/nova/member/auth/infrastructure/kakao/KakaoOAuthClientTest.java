package com.grandis.nova.member.auth.infrastructure.kakao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.member.auth.AuthErrorCode;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 정상 응답 파싱, 4xx → INVALID_OAUTH_CALLBACK, 타임아웃. 카카오 없이 MockRestServiceServer 로 돈다.
 * 응답 JSON 은 실제 카카오로 실측한 형태와 같다(토큰: access_token·token_type·refresh_token·expires_in·scope, 오류: error·error_description·error_code).
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("KakaoOAuthClient (MockRestServiceServer)")
class KakaoOAuthClientTest {

    private static final String TOKEN_URI = "https://kauth.kakao.com/oauth/token";
    private static final String ME_URI = "https://kapi.kakao.com/v2/user/me";
    private static final String REDIRECT = "http://localhost:3000/login/kakao/callback";

    private MockRestServiceServer server;
    private KakaoOAuthClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KakaoOAuthClient(builder.build(), new KakaoProperties(
                "client-id", "client-secret", TOKEN_URI, ME_URI, List.of(REDIRECT), null, null));
    }

    @Test
    @DisplayName("code 교환: form 으로 grant_type·client_id·client_secret·redirect_uri·code 를 보내고 access_token 을 돌려준다")
    void exchangeCodeSendsFormAndReturnsAccessToken() {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", org.hamcrest.Matchers.startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE)))
                .andExpect(content().formData(form()))
                .andRespond(withSuccess("""
                        {"access_token":"kakao-at","token_type":"bearer","refresh_token":"kakao-rt","expires_in":21599,"scope":"profile_nickname"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.exchangeCode("the-code", REDIRECT)).isEqualTo("kakao-at");
        server.verify();
    }

    private static org.springframework.util.MultiValueMap<String, String> form() {
        var m = new org.springframework.util.LinkedMultiValueMap<String, String>();
        m.add("grant_type", "authorization_code");
        m.add("client_id", "client-id");
        m.add("client_secret", "client-secret");
        m.add("redirect_uri", REDIRECT);
        m.add("code", "the-code");
        return m;
    }

    @Test
    @DisplayName("허용 목록 밖 redirect_uri 는 카카오를 부르지도 않고 400 INVALID_OAUTH_CALLBACK")
    void rejectsUnknownRedirectUri() {
        assertThatThrownBy(() -> client.exchangeCode("the-code", "https://evil.example/cb"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(AuthErrorCode.INVALID_OAUTH_CALLBACK);
        server.verify();   // 기대 요청 0건
    }

    @Test
    @DisplayName("카카오가 4xx(만료·재사용 code, KOE320) 를 주면 400 INVALID_OAUTH_CALLBACK 하나로 뭉친다")
    void tokenEndpoint4xxIsInvalidCallback() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"invalid_grant\",\"error_description\":\"authorization code not found\",\"error_code\":\"KOE320\"}"));

        assertThatThrownBy(() -> client.exchangeCode("stale", REDIRECT))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(AuthErrorCode.INVALID_OAUTH_CALLBACK);
    }

    @Test
    @DisplayName("4xx 로그에는 error·error_code 만 남고 본문 원문(인가 code 포함)은 안 남는다. JSON 이 아니면 unparseable")
    void fourXxLogCarriesOnlyErrorFields(CapturedOutput output) {
        server.expect(requestTo(TOKEN_URI)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"invalid_grant\",\"error_description\":\"authorization code not found for code=SECRETCODE123\",\"error_code\":\"KOE320\"}"));

        assertThatThrownBy(() -> client.exchangeCode("stale", REDIRECT)).isInstanceOf(BusinessException.class);

        assertThat(output.getOut()).contains("error=invalid_grant error_code=KOE320 bodyLength=").doesNotContain("SECRETCODE123").doesNotContain("authorization code not found");
        assertThat(KakaoOAuthClient.errorSummary("<html>maintenance</html>")).isEqualTo("unparseable bodyLength=24");
        assertThat(KakaoOAuthClient.errorSummary(null)).isEqualTo("unparseable bodyLength=0");
    }

    @Test
    @DisplayName("카카오 5xx 도 같은 오류. 사용자 조치는 다시 로그인뿐이다")
    void tokenEndpoint5xxIsInvalidCallback() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withServerError());

        assertThatThrownBy(() -> client.exchangeCode("c", REDIRECT)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("타임아웃(연결 불가)도 같은 오류로 뭉친다")
    void timeoutIsInvalidCallback() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> client.exchangeCode("c", REDIRECT))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(AuthErrorCode.INVALID_OAUTH_CALLBACK);
    }

    @Test
    @DisplayName("실측: 200 인데 본문이 HTML(점검·CDN 오류 페이지)이면 500 이 아니라 400 INVALID_OAUTH_CALLBACK 이다")
    void htmlBodyWith200IsInvalidCallback() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withSuccess("<html><body>maintenance</body></html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client.exchangeCode("c", REDIRECT))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(AuthErrorCode.INVALID_OAUTH_CALLBACK);
    }

    @Test
    @DisplayName("실측: 200 인데 JSON 이 잘려 있어도 400 으로 뭉친다")
    void truncatedJsonWith200IsInvalidCallback() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withSuccess("{\"access_token\":\"ka", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.exchangeCode("c", REDIRECT)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("access_token 이 빠진 200 응답은 오류다")
    void tokenResponseWithoutAccessTokenIsInvalid() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withSuccess("{\"token_type\":\"bearer\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.exchangeCode("c", REDIRECT)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("사용자 조회: Bearer 헤더로 부르고 id·kakao_account.profile.nickname·profile_image_url 을 읽는다")
    void fetchUserParsesIdAndProfile() {
        server.expect(requestTo(ME_URI))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer kakao-at"))
                .andRespond(withSuccess("""
                        {"id":1234567890,"connected_at":"2026-09-19T00:00:00Z",
                         "properties":{"nickname":"홍길동"},
                         "kakao_account":{"profile_nickname_needs_agreement":false,
                                          "profile":{"nickname":"홍길동","thumbnail_image_url":"https://k.kakaocdn.net/t.jpg","profile_image_url":"https://k.kakaocdn.net/p.jpg","is_default_image":false}}}
                        """, MediaType.APPLICATION_JSON));

        KakaoUserInfo user = client.fetchUser("kakao-at");

        assertThat(user.id()).isEqualTo("1234567890");
        assertThat(user.nickname()).isEqualTo("홍길동");
        assertThat(user.profileImageUrl()).isEqualTo("https://k.kakaocdn.net/p.jpg");
    }

    @Test
    @DisplayName("사용자 조회: 프로필 동의가 없어 profile 이 비어 있어도 id 만 있으면 된다 (닉네임 null)")
    void fetchUserWithoutProfile() {
        server.expect(requestTo(ME_URI)).andRespond(withSuccess("{\"id\":42,\"kakao_account\":{}}", MediaType.APPLICATION_JSON));

        KakaoUserInfo user = client.fetchUser("kakao-at");

        assertThat(user.id()).isEqualTo("42");
        assertThat(user.nickname()).isNull();
    }

    @Test
    @DisplayName("사용자 조회: 64비트를 넘는 큰 id 도 문자열로 그대로 받는다 (Jackson 이 BigInteger 로 줘도 Number)")
    void fetchUserWithHugeId() {
        server.expect(requestTo(ME_URI)).andRespond(withSuccess("{\"id\":18446744073709551615,\"kakao_account\":{}}", MediaType.APPLICATION_JSON));

        assertThat(client.fetchUser("kakao-at").id()).isEqualTo("18446744073709551615");
    }

    @Test
    @DisplayName("실측: 카카오가 응답을 안 주면 KakaoClientConfiguration 의 읽기 타임아웃 안팎에 400 으로 끝난다 — 로그인 스레드가 안 잠긴다")
    void readTimeoutActuallyApplies() throws Exception {
        try (java.net.ServerSocket silent = new java.net.ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                try { while (!silent.isClosed()) { silent.accept(); } } catch (java.io.IOException ignored) { }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            KakaoProperties props = new KakaoProperties("id", "secret",
                    "http://localhost:" + silent.getLocalPort() + "/oauth/token", ME_URI, List.of(REDIRECT),
                    java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(300));
            KakaoOAuthClient real = new KakaoOAuthClient(new KakaoClientConfiguration().kakaoRestClient(props), props);
            long started = System.nanoTime();

            assertThatThrownBy(() -> real.exchangeCode("c", REDIRECT)).isInstanceOf(BusinessException.class);

            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(3_000);
        }
    }

    @Test
    @DisplayName("사용자 조회: id 가 없거나 숫자가 아니면 오류")
    void fetchUserWithoutIdIsInvalid() {
        server.expect(requestTo(ME_URI)).andRespond(withSuccess("{\"kakao_account\":{}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.fetchUser("kakao-at")).isInstanceOf(BusinessException.class);

        server.reset();
        server.expect(requestTo(ME_URI)).andRespond(withSuccess("{\"id\":\"1234\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.fetchUser("kakao-at")).isInstanceOf(BusinessException.class);
    }
}
