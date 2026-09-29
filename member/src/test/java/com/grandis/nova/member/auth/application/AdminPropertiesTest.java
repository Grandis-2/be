package com.grandis.nova.member.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * password-hash 형식 검사가 기동을 막는가 · 값을 흘리지 않는가 실측. 검사는 생성자에 있다(바인딩 검증은 실패 보고서에 값을 찍는다). cost 12 이상도 여기서 강제된다.
 */
@DisplayName("AdminProperties — bcrypt 형식·cost 검사")
class AdminPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AdminProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class)
            .withPropertyValues("admin.username=admin");

    private static String rootMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            sb.append(cur.getMessage()).append(" | ");
        }
        return sb.toString();
    }

    @Test
    @DisplayName("cost 12 해시는 바인딩된다")
    void cost12Binds() {
        String hash = new BCryptPasswordEncoder(12).encode("pw");
        runner.withPropertyValues("admin.password-hash=" + hash).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(AdminProperties.class).passwordHash()).isEqualTo(hash);
        });
    }

    @Test
    @DisplayName("평문이면 기동이 실패하고 메시지가 이유를 말한다")
    void plainTextFailsStartup() {
        runner.withPropertyValues("admin.password-hash=plain-password").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessage(ctx.getStartupFailure())).contains("admin.password-hash must be a bcrypt hash");
        });
    }

    @Test
    @DisplayName("cost 11 은 경계 바로 아래라 기동이 실패한다")
    void cost11FailsStartup() {
        runner.withPropertyValues("admin.password-hash=$2a$11$" + "x".repeat(53)).run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("cost 4 해시는 bcrypt 형식이지만 12 미만이라 기동이 실패한다")
    void lowCostFailsStartup() {
        String weak = new BCryptPasswordEncoder(4).encode("pw");
        assertThat(weak).startsWith("$2a$04$");
        runner.withPropertyValues("admin.password-hash=" + weak).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessage(ctx.getStartupFailure())).contains("cost 12..31");
        });
    }

    @Test
    @DisplayName("bcrypt cost 는 31 이 상한이다: 32 는 형식상 숫자 두 자리여도 기동 거부, 31 은 통과")
    void costUpperBound() {
        String body = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0";   // 53자
        runner.withPropertyValues("admin.password-hash=$2a$32$" + body).run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("admin.password-hash=$2a$31$" + body).run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("toString 은 해시를 찍지 않는다")
    void toStringMasksHash() {
        String hash = new BCryptPasswordEncoder(12).encode("pw");
        assertThat(new AdminProperties("admin", hash).toString()).doesNotContain(hash).contains("****");
    }

    @Test
    @DisplayName("기동 실패 로그에 거부된 값이 찍히지 않는다 — 평문 · 약한 해시 모두. Boot 가 보고서를 만드는 두 분석기와 예외 사슬 전부를 본다")
    void rejectedValueNeverReachesTheFailureReport() {
        String plain = "MyPlainSecret123!";
        String weak = new BCryptPasswordEncoder(4).encode("pw");
        for (String secret : new String[] {plain, weak}) {
            runner.withPropertyValues("admin.password-hash=" + secret).run(ctx -> {
                assertThat(ctx).hasFailed();
                Throwable failure = ctx.getStartupFailure();
                assertThat(rootMessage(failure)).as("예외 사슬의 메시지").doesNotContain(secret).contains("admin.password-hash must be a bcrypt hash");
                int reports = 0;
                for (String analyzer : new String[] {
                        "org.springframework.boot.diagnostics.analyzer.BindValidationFailureAnalyzer",
                        "org.springframework.boot.diagnostics.analyzer.BindFailureAnalyzer"}) {
                    FailureAnalysis analysis = analyze(analyzer, failure);
                    if (analysis != null) {
                        reports++;
                        assertThat(analysis.getDescription() + analysis.getAction()).as(analyzer).doesNotContain(secret)
                                .contains("admin.password-hash must be a bcrypt hash");
                    }
                }
                assertThat(reports).as("보고서를 만든 분석기가 있다 — 둘 다 null 이면 이 시험은 아무것도 안 본 것").isPositive();
            });
        }
    }

    @Test
    @DisplayName("대조군 — 바인딩 검증(@Pattern)이었다면 보고서에 값이 찍힌다. 위 시험의 분석기 경로가 실제로 값을 내는 경로임을 보인다")
    void controlBindingValidationWouldLeak() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(LeakyConfig.class)
                .withPropertyValues("leaky.password-hash=MyPlainSecret123!")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    FailureAnalysis analysis = analyze("org.springframework.boot.diagnostics.analyzer.BindValidationFailureAnalyzer",
                            ctx.getStartupFailure());
                    assertThat(analysis).isNotNull();
                    assertThat(analysis.getDescription()).contains("MyPlainSecret123!");
                });
    }

    @org.springframework.validation.annotation.Validated
    @org.springframework.boot.context.properties.ConfigurationProperties("leaky")
    record LeakyProperties(@jakarta.validation.constraints.Pattern(regexp = "^\\$2.*") String passwordHash) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LeakyProperties.class)
    static class LeakyConfig {
    }

    /** Boot 가 기동 실패를 보고할 때 쓰는 분석기(패키지 비공개 생성자)를 그대로 돌린다. */
    private static FailureAnalysis analyze(String analyzerClass, Throwable failure) {
        try {
            var constructor = Class.forName(analyzerClass).getDeclaredConstructor();
            constructor.setAccessible(true);
            return ((FailureAnalyzer) constructor.newInstance()).analyze(failure);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
