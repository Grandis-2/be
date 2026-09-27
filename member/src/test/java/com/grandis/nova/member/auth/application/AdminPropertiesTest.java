package com.grandis.nova.member.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * "@Validated + @ConfigurationProperties record 의 @Pattern 위반이 기동을 막는가" 실측. cost 12 이상도 여기서 강제된다.
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
}
