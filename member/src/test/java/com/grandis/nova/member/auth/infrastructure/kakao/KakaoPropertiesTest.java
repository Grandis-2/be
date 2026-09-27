package com.grandis.nova.member.auth.infrastructure.kakao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/** token-uri·user-info-uri 는 https 만. http 면 기동이 실패한다(client_secret·code·액세스 토큰이 평문으로 나가는 설정을 막는다). */
@DisplayName("KakaoProperties — https 강제")
class KakaoPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KakaoProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class)
            .withPropertyValues("kakao.client-id=cid", "kakao.client-secret=cs",
                    "kakao.allowed-redirect-uris=http://localhost:3000/login/kakao/callback");

    private static String rootMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            sb.append(cur.getMessage()).append(" | ");
        }
        return sb.toString();
    }

    @Test
    @DisplayName("https 둘이면 바인딩된다 (redirect-uri 는 로컬 프론트라 http 여도 된다)")
    void httpsBinds() {
        runner.withPropertyValues("kakao.token-uri=https://kauth.kakao.com/oauth/token", "kakao.user-info-uri=https://kapi.kakao.com/v2/user/me")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("token-uri 가 http 면 기동 실패, user-info-uri 가 http 여도 기동 실패")
    void httpFailsStartup() {
        runner.withPropertyValues("kakao.token-uri=http://kauth.kakao.com/oauth/token", "kakao.user-info-uri=https://kapi.kakao.com/v2/user/me")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootMessage(ctx.getStartupFailure())).contains("kakao.token-uri must use https");
                });
        runner.withPropertyValues("kakao.token-uri=https://kauth.kakao.com/oauth/token", "kakao.user-info-uri=http://kapi.kakao.com/v2/user/me")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootMessage(ctx.getStartupFailure())).contains("kakao.user-info-uri must use https");
                });
    }

    @Test
    @DisplayName("toString 은 client secret 을 찍지 않는다")
    void toStringMasksSecret() {
        String text = new KakaoProperties("cid", "very-secret-value", "https://a", "https://b", java.util.List.of("http://localhost:3000/cb"), null, null).toString();
        assertThat(text).doesNotContain("very-secret-value").contains("clientSecret=****").contains("cid");
    }
}
