package com.grandis.nova.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration; // Boot 4: spring-boot-validation 모듈로 이동
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 설정 바인딩과 Clock 빈. "@ConditionalOnMissingBean 이 일반 @Configuration 에서 동작하는가" 를 여기서 잰다.
 * 스프링 부트는 그 조건을 자동설정 클래스에서만 쓰라고 하므로, 앱이 Clock 을 따로 정의했을 때 빈이 하나인지 둘인지 실측한다.
 */
@DisplayName("JwtConfiguration · JwtProperties 바인딩")
class JwtConfigurationTest {

    private static final String PEM = TestKeys.privatePem(TestKeys.ISSUER);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(JwtConfiguration.class);

    private ApplicationContextRunner valid() {
        return runner.withPropertyValues(
                "jwt.issuer=nova",
                "jwt.key-id=" + TestKeys.KID,
                "jwt.private-key=" + PEM,
                "jwt.access-token-validity=1h",
                "jwt.refresh-token-validity=14d");
    }

    @Test
    @DisplayName("yml 의 1h·14d 가 Duration 으로 바인딩되고 Clock 빈이 생긴다")
    void bindsDurationsAndProvidesClock() {
        valid().run(ctx -> {
            JwtProperties p = ctx.getBean(JwtProperties.class);
            assertThat(p.issuer()).isEqualTo("nova");
            assertThat(p.accessTokenValidity()).isEqualTo(Duration.ofHours(1));
            assertThat(p.refreshTokenValidity()).isEqualTo(Duration.ofDays(14));
            assertThat(ctx).hasSingleBean(Clock.class);
        });
    }

    // 실패 "이유" 까지 단언한다. hasFailed() 만 보면 다른 이유로 실패해도 통과한다(리뷰 메모 ㄱ).
    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        StringBuilder sb = new StringBuilder();
        while (cur != null) {
            sb.append(cur.getMessage()).append(" | ");
            cur = cur.getCause();
        }
        return sb.toString();
    }

    @Test
    @DisplayName("private-key 가 CHANGE_ME 자리표시자면 기동이 실패한다 (예시 파일을 그대로 복사한 경우)")
    void placeholderPrivateKeyFailsStartup() {
        valid().withPropertyValues("jwt.private-key=CHANGE_ME").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessage(ctx.getStartupFailure())).contains("jwt.private-key is still the example placeholder");
        });
    }

    @Test
    @DisplayName("private-key 는 있는데 key-id 가 없으면 기동이 실패한다 — kid 없는 토큰은 아무도 검증 못 한다")
    void privateKeyWithoutKeyIdFailsStartup() {
        valid().withPropertyValues("jwt.key-id=").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessage(ctx.getStartupFailure())).contains("jwt.key-id is required");
        });
    }

    @Test
    @DisplayName("키 소스가 하나도 없으면 기동이 실패한다")
    void noKeySourceFailsStartup() {
        runner.withPropertyValues("jwt.issuer=nova", "jwt.access-token-validity=1h", "jwt.refresh-token-validity=14d").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessage(ctx.getStartupFailure())).contains("no key source");
        });
    }

    @Test
    @DisplayName("jwk-set-uri 가 http 면 기동 실패. jwk-set-allow-http=true 를 명시해야 받는다 (기본값으로 http 가 들어가는 일이 없다)")
    void plainHttpJwkSetUriNeedsExplicitAllow() {
        runner.withPropertyValues("jwt.issuer=nova", "jwt.access-token-validity=1h", "jwt.refresh-token-validity=14d",
                        "jwt.jwk-set-uri=http://member:8080/.well-known/jwks.json")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(rootMessage(ctx.getStartupFailure())).contains("jwt.jwk-set-uri must use https");
                });
        runner.withPropertyValues("jwt.issuer=nova", "jwt.access-token-validity=1h", "jwt.refresh-token-validity=14d",
                        "jwt.jwk-set-uri=http://member:8080/.well-known/jwks.json", "jwt.jwk-set-allow-http=true")
                .run(ctx -> assertThat(ctx).hasNotFailed());
        runner.withPropertyValues("jwt.issuer=nova", "jwt.access-token-validity=1h", "jwt.refresh-token-validity=14d",
                        "jwt.jwk-set-uri=https://member.example/.well-known/jwks.json")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("검증 전용(public-keys 만): 기동은 되고 JwtKeyRing 은 서명 못 함")
    void verifierOnlyBoots() {
        runner.withPropertyValues("jwt.issuer=nova", "jwt.access-token-validity=1h", "jwt.refresh-token-validity=14d",
                        "jwt.public-keys." + TestKeys.KID + "=" + TestKeys.publicPem(TestKeys.ISSUER))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(JwtKeyRing.class).canSign()).isFalse();
                    assertThat(ctx.getBean(JwtKeyRing.class).resolve(TestKeys.KID)).isPresent();
                });
    }

    @Test
    @DisplayName("issuer 가 없으면 기동이 실패한다")
    void missingIssuerFailsStartup() {
        runner.withPropertyValues(
                "jwt.key-id=" + TestKeys.KID,
                "jwt.private-key=" + PEM,
                "jwt.access-token-validity=1h",
                "jwt.refresh-token-validity=14d").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessage(ctx.getStartupFailure())).contains("issuer");
        });
    }

    @Test
    @DisplayName("access-token-validity 가 0 이나 음수면 기동이 실패한다 — 통과하면 발급 즉시 만료 토큰이 나간다")
    void nonPositiveValidityFailsStartup() {
        valid().withPropertyValues("jwt.access-token-validity=0s").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessage(ctx.getStartupFailure())).contains("jwt.access-token-validity must be positive");
        });
        valid().withPropertyValues("jwt.refresh-token-validity=-1h").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessage(ctx.getStartupFailure())).contains("jwt.refresh-token-validity must be positive");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class AppClockConfig {
        @Bean
        @Primary
        Clock appClock() {
            return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Test
    @DisplayName("앱이 @Primary 로 Clock 을 정의하면 그것이 주입된다 (실측: @ConditionalOnMissingBean 은 일반 설정에서 빈이 둘이 됐다)")
    void appDefinedPrimaryClockWins() {
        valid().withUserConfiguration(AppClockConfig.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeansOfType(Clock.class)).hasSize(2);
            assertThat(ctx.getBean(Clock.class).instant()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class AppClockWithoutPrimaryConfig {
        @Bean
        Clock appClock() {
            return Clock.systemUTC();
        }
    }

    @Test
    @DisplayName("실측: @Primary 없이 Clock 을 또 정의하면 기동이 실패한다 — JwtKeyRing 빈이 Clock 을 주입받으므로(RS256 전환 뒤) 주입받는 빈이 항상 있다")
    void appDefinedClockWithoutPrimary() {
        // RS256 전환 전에는 "빈 둘만으로는 조용히 뜨고 JwtTokenProvider 가 있어야 실패" 였다. 이제 JwtConfiguration 자체가 Clock 을 주입받는 빈(JwtKeyRing)을 두므로
        // 어느 조합이든 기동에서 걸린다. 더 이른 실패라 그대로 둔다.
        valid().withUserConfiguration(AppClockWithoutPrimaryConfig.class).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessage(ctx.getStartupFailure()))
                    .contains("java.time.Clock")
                    .contains("expected single matching bean but found 2");
        });
    }

    @Test
    @DisplayName("toString 은 개인키를 찍지 않는다")
    void toStringMasksSecret() {
        JwtProperties p = TestKeys.issuerProperties("nova", Duration.ofHours(1), Duration.ofDays(14));
        assertThat(p.toString()).doesNotContain("BEGIN PRIVATE KEY").contains("privateKey=****");
    }
}
