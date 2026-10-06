package com.grandis.nova.common.jpa;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Metamodel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.beans.factory.support.BeanDefinitionOverrideException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAccessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 자동설정이 서비스 컨텍스트에서 어떻게 엮이는가. common:security 의 clock(조건 없는 일반 빈)과 함께 있는 경우를 흉내 낸다.
 * Auditing 은 JPA 메타모델을 요구하므로 엔티티 없는 EntityManagerFactory 하나를 둔다 — DB 왕복은 order 의 StoredTimestampTest 가 본다.
 */
class JpaAuditingAutoConfigurationTest {

    /** 마이크로초 아래가 있는 시각. 저장 시계를 거치지 않으면 그대로 나온다. */
    static final Instant NANOS = Instant.parse("2026-01-01T00:00:00.000000900Z");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JpaAuditingAutoConfiguration.class))
            .withBean("entityManagerFactory", EntityManagerFactory.class, JpaAuditingAutoConfigurationTest::emptyEntityManagerFactory);

    static EntityManagerFactory emptyEntityManagerFactory() {
        EntityManagerFactory factory = mock(EntityManagerFactory.class);
        when(factory.getMetamodel()).thenReturn(mock(Metamodel.class));
        return factory;
    }

    @Test
    @DisplayName("Clock 을 주입받는 곳에는 저장 시계가 들어간다 — 공통 clock 빈이 함께 있어도")
    void primaryStorageClockWinsOverSecurityClock() {
        runner.withUserConfiguration(SecurityLikeClock.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeansOfType(Clock.class)).containsOnlyKeys("clock", "storageClock");
            assertThat(ctx.getBean(Clock.class)).isEqualTo(StorageClock.atStorageResolution(Clock.systemUTC()));
        });
    }

    /*
     * 주입 파라미터 이름이 clock 이라 @Primary 가 없으면 이름이 같은 공통 clock(나노초 고정)이 들어간다.
     * 그 시각이 나오지 않고 마이크로초로 내린 시각이 나와야 한다.
     */
    @Test
    @DisplayName("Auditing 시각도 저장 시계에서 온다")
    void auditingUsesStorageClock() {
        runner.withUserConfiguration(SecurityLikeClock.class).run(ctx -> {
            assertThat(ctx).hasBean("jpaAuditingHandler");
            TemporalAccessor now = ctx.getBean("auditingDateTimeProvider", DateTimeProvider.class).getNow().orElseThrow();

            Instant instant = Instant.from(now);
            assertThat(instant).isNotEqualTo(NANOS);
            assertThat(instant.getNano() % 1_000).isZero();
        });
    }

    @Test
    @DisplayName("공통 clock 이 없는 서비스(common:security 미사용)에서도 뜬다")
    void worksWithoutSecurityClock() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(Clock.class);
        });
    }

    @Test
    @DisplayName("실측: 앱이 @EnableJpaAuditing 을 또 켜면 기동이 실패한다 — 조용히 두 번 켜지지 않는다")
    void appEnablingAuditingAgainFailsFast() {
        runner.withUserConfiguration(AppEnablesAuditing.class).run(ctx ->
                assertThat(ctx).hasFailed().getFailure()
                        .isInstanceOf(BeanDefinitionOverrideException.class)
                        .hasMessageContaining("jpaAuditingHandler"));
    }

    @Configuration(proxyBeanMethods = false)
    static class SecurityLikeClock {

        @Bean
        Clock clock() {
            return Clock.fixed(NANOS, ZoneOffset.UTC);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaAuditing
    static class AppEnablesAuditing {
    }
}
