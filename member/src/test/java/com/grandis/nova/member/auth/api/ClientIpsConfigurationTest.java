package com.grandis.nova.member.auth.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.member.auth.application.AdminLoginLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 컨테이너가 X-Forwarded-For 를 고쳐 쓰는 설정이면 기동하지 않는다. Boot 는 `AWS_EXECUTION_ENV` 가 `AWS_ECS` 로 시작하면(ECS · Fargate)
 * 전략을 비워 둬도 스스로 켠다 — 그 경우를 환경 값으로 재현한다.
 */
@DisplayName("ClientIps 기동 가드 — X-Forwarded-For 를 받은 그대로 읽을 수 있을 때만 뜬다")
class ClientIpsConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AdminLoginLimit.class)
    static class Limit {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Limit.class, ClientIpsConfiguration.class);

    @Test
    @DisplayName("ECS 에서 전략을 비워 두면 Boot 가 켜므로 실패, none 을 명시하면 뜬다")
    void ecsRequiresExplicitNone() {
        runner.withPropertyValues("AWS_EXECUTION_ENV=AWS_ECS_FARGATE").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("server.forward-headers-strategy=none");
        });
        runner.withPropertyValues("AWS_EXECUTION_ENV=AWS_ECS_FARGATE", "server.forward-headers-strategy=none")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(ClientIps.class));
    }

    @Test
    @DisplayName("전략을 native · framework 로 켜면 실패. 믿는 프록시 수가 0 이면(헤더를 안 읽음) 켜져 있어도 뜬다")
    void enabledStrategyConflictsOnlyWhenHeadersAreRead() {
        runner.withPropertyValues("server.forward-headers-strategy=native").run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("server.forward-headers-strategy=framework").run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("server.forward-headers-strategy=native", "auth.admin-login-limit.trusted-proxy-hops=0")
                .run(ctx -> assertThat(ctx).hasNotFailed());
        runner.run(ctx -> assertThat(ctx).as("로컬 — 클라우드 감지 없음, 전략 비움").hasNotFailed());
    }
}
