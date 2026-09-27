package com.grandis.nova.member.auth.infrastructure.kakao;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.member.auth.AuthErrorCode;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 카카오 REST API 두 번: code → 토큰, 토큰 → 사용자. spring-security-oauth2-client 없이 RestClient 로 한다.
 * 카카오 토큰은 여기서 쓰고 버린다. 저장하지 않는다.
 *
 * 실패는 전부 AuthErrorCode.INVALID_OAUTH_CALLBACK(400) 하나로 뭉친다. 카카오의 오류 코드(KOE320 등)는 로그에만 남긴다 —
 * 사용자에게는 "다시 로그인" 이 유일한 조치이고, 원문을 응답에 실으면 카카오 쪽 정보가 샌다.
 * 응답 구조는 실제 카카오 앱으로 실측했다(2026-09-19): 토큰 응답은 access_token·token_type·refresh_token·expires_in·scope·refresh_token_expires_in,
 * 사용자 응답의 id 는 JSON 숫자, 닉네임은 kakao_account.profile.nickname, profile_image_url 은 동의 항목을 안 켜면 키 자체가 없다.
 * code 재사용은 400 KOE320(invalid_grant), client_secret 누락은 401 KOE010(invalid_client).
 */
@Component
public class KakaoOAuthClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoOAuthClient.class);
    private static final Pattern ERROR_FIELD = Pattern.compile("\"(error|error_code)\"\\s*:\\s*\"([A-Za-z0-9_]{1,40})\"");

    private final RestClient rest;
    private final KakaoProperties properties;

    public KakaoOAuthClient(RestClient kakaoRestClient, KakaoProperties properties) {
        this.rest = kakaoRestClient;
        this.properties = properties;
    }

    /** 인가 code 를 카카오 액세스 토큰으로 바꾼다. redirect_uri 는 인가 요청 때 쓴 것과 같아야 카카오가 받아 준다. */
    public String exchangeCode(String code, String redirectUri) {
        if (!properties.allowedRedirectUris().contains(redirectUri)) {
            // 클라이언트가 고른 문자열이다. 목록 밖이면 값 자체가 쓸모없으니 로그에 싣지 않는다 — 개행이 들어오면 로그가 위조된다(X-Trace-Id 와 같은 규칙).
            log.warn("kakao redirect_uri not in allowlist (length={})", redirectUri == null ? 0 : redirectUri.length());
            throw new BusinessException(AuthErrorCode.INVALID_OAUTH_CALLBACK);
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());   // client_secret_post: 본문에 실린다
        form.add("redirect_uri", redirectUri);
        form.add("code", code);
        TokenResponse response = call(() -> rest.post()
                .uri(properties.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class), "token");
        if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
            log.warn("kakao token response without access_token");
            throw new BusinessException(AuthErrorCode.INVALID_OAUTH_CALLBACK);
        }
        return response.accessToken();
    }

    /** 카카오 액세스 토큰으로 회원번호·닉네임·프로필 사진을 읽는다. */
    public KakaoUserInfo fetchUser(String kakaoAccessToken) {
        Map<String, Object> body = call(() -> rest.get()
                .uri(properties.userInfoUri())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + kakaoAccessToken)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { }), "user");
        // id 는 64비트 정수. Jackson 이 Long 또는 BigInteger 로 준다. 둘 다 Number 라 toString 으로 받는다 — 문자열 id 가 와도 거절한다.
        if (body == null || !(body.get("id") instanceof Number id)) {
            log.warn("kakao user response without numeric id");
            throw new BusinessException(AuthErrorCode.INVALID_OAUTH_CALLBACK);
        }
        Map<String, Object> profile = map(map(body, "kakao_account"), "profile");
        return new KakaoUserInfo(id.toString(), string(profile, "nickname"), string(profile, "profile_image_url"));
    }

    private <T> T call(java.util.function.Supplier<T> request, String what) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            // 4xx: code 만료·재사용·redirect_uri 불일치(KOE303/KOE320). 5xx: 카카오 장애. 둘 다 사용자 조치는 같다.
            // 로그에는 error·error_code 두 필드만. 본문 원문은 싣지 않는다 — KOE320 본문에 인가 code 원문이 들어오는 것을 실측에서 봤고
            // (쓰인 code 라 값은 없지만) 다른 4xx 가 안 쓰인 code 를 싣지 않는다는 보장이 없다. 요청 본문(client_secret 포함)은 어디에도 찍지 않는다.
            log.warn("kakao {} call failed status={} {}", what, e.getStatusCode().value(), errorSummary(e.getResponseBodyAsString()));
            throw new BusinessException(AuthErrorCode.INVALID_OAUTH_CALLBACK);
        } catch (ResourceAccessException e) {
            // 연결·읽기 타임아웃. 메시지에는 URI 와 원인 클래스가 들어가고 요청 본문은 없다.
            log.warn("kakao {} call unreachable: {}", what, e.getMessage());
            throw new BusinessException(AuthErrorCode.INVALID_OAUTH_CALLBACK);
        } catch (RestClientException e) {
            // 상태는 200 인데 본문을 못 읽는 경우: 점검·CDN 오류 페이지(200 text/html → UnknownContentTypeException), 잘린 JSON.
            // 장애 중 제일 흔한 모양인데 앞의 둘에 안 걸린다. 실측: KakaoOAuthClientTest.htmlBodyWith200IsInvalidCallback.
            log.warn("kakao {} response unreadable: {}", what, e.getClass().getSimpleName());
            throw new BusinessException(AuthErrorCode.INVALID_OAUTH_CALLBACK);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> source, String key) {
        return source != null && source.get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String string(Map<String, Object> source, String key) {
        Object v = source.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 카카오 오류 본문에서 error·error_code 만 뽑는다. 값은 영숫자·밑줄만 받는다(로그 위조 방지). JSON 이 아니거나 두 필드가 없으면 "unparseable".
     * 어느 쪽이든 bodyLength 를 같이 남긴다 — 카카오가 오류 모양을 바꾸는 날 "본문이 오긴 왔다" 는 구분이라도 남게.
     */
    static String errorSummary(String body) {
        int length = body == null ? 0 : body.length();
        StringBuilder sb = new StringBuilder();
        if (body != null) {
            Matcher m = ERROR_FIELD.matcher(body);
            while (m.find()) {
                sb.append(m.group(1)).append('=').append(m.group(2)).append(' ');
            }
        }
        return (sb.isEmpty() ? "unparseable" : sb.toString().strip()) + " bodyLength=" + length;
    }

    /** 카카오 토큰 응답. JSON 키를 명시해 네이밍 전략 설정에 안 흔들리게 한다. 애너테이션은 Jackson 3 에서도 com.fasterxml 패키지에 남아 있다(실측). */
    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") Integer expiresIn) {
    }
}
