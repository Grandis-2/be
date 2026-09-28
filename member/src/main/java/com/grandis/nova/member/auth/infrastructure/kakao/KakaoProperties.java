package com.grandis.nova.member.auth.infrastructure.kakao;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 프론트 주도 코드 교환: 프론트가 카카오에서 받은 code 를 우리에게 넘기고, 우리가 카카오 토큰 엔드포인트에 code 를 낸다.
 * client secret 은 여기(서버)에만 있다. redirect_uri 는 카카오가 토큰 교환 때 인가 요청과 같은 값인지 대조하므로 프론트가 보낸 값을
 * 그대로 쓰되, allowed-redirect-uris 목록 밖이면 거절한다 — 아무 URI 나 받으면 code 가 다른 사이트로 새는 통로가 된다.
 * 카카오 응답이 늦으면 접수와 무관한 로그인 스레드만 잠기지만, 그래도 상한을 둔다.
 * token-uri·user-info-uri 는 https 만 받는다 — 토큰 엔드포인트에는 client_secret 과 code 가, 사용자 엔드포인트에는 액세스 토큰이 실린다.
 * http 로 설정하면 평문 전송이 되므로 바인딩에서 막는다(CodeRabbit 지적, CWE-319). 실측: KakaoPropertiesTest.
 * **설정 바인딩에서만 막는다**(생성자가 아니라 @Pattern). 시험이 `new KakaoProperties(...)` 로 로컬 http 서버를 가리켜 타임아웃을 재기 때문이다 —
 * JwtProperties 의 CHANGE_ME 는 생성자에서 막는 것과 방식이 다른 이유가 이것이다. 생성자로 옮기면 그 시험이 깨진다.
 */
@Validated
@ConfigurationProperties("kakao")
public record KakaoProperties(
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotBlank @Pattern(regexp = "^https://.+", message = "kakao.token-uri must use https") String tokenUri,
        @NotBlank @Pattern(regexp = "^https://.+", message = "kakao.user-info-uri must use https") String userInfoUri,
        @NotEmpty List<String> allowedRedirectUris,
        Duration connectTimeout,
        Duration readTimeout
) {
    public KakaoProperties {
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(2);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(5);
        }
    }

    /** client secret 이 로그·오류 메시지에 찍히지 않게. record 기본 toString 은 전부 찍는다. */
    @Override
    public String toString() {
        return "KakaoProperties[clientId=" + clientId + ", clientSecret=****, tokenUri=" + tokenUri + ", userInfoUri=" + userInfoUri
                + ", allowedRedirectUris=" + allowedRedirectUris + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }
}
