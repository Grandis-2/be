package com.grandis.nova.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * 서비스의 시계와 JPA Auditing. 원장 · 아웃박스 · Auditing(created_at · updated_at) 이 모두 이 {@link Clock} 하나로 시각을 찍는다 —
 * Auditing 을 안 켜면 NOT NULL 위반으로만 드러나고, 시계가 둘이면 코드가 찍은 시각과 Auditing 시각이 어긋난다.
 *
 * 시계는 저장 해상도(마이크로초)로 내린 것이다({@link #atStorageResolution}). 빈 이름이 clock 이 아니라 storageClock 인 것은
 * common:security 의 JwtConfiguration 이 조건 없이 clock(systemUTC) 빈을 두기 때문이다 — 같은 이름이면 기동이 실패하고 그쪽은
 * 지울 수 없다. 그래서 두 빈이 함께 있고, @Primary 가 모든 주입 지점(JWT 키 링 포함)을 이 시계로 모은다(JwtConfiguration 이 권하는 방식).
 * @Primary 를 지우면 Clock 빈이 둘이라 기동이 실패하거나, 파라미터 이름이 clock 인 주입 지점에 공통 시계가 들어가 보정이 사라진다.
 * OrderApplicationTest 가 잡는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    /** DB 시각 칼럼(datetime(6))이 담는 가장 작은 단위. */
    static final Duration STORAGE_RESOLUTION = Duration.ofNanos(1_000);

    @Bean
    @Primary
    Clock storageClock() {
        return atStorageResolution(Clock.systemUTC());
    }

    @Bean
    DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant());
    }

    /**
     * 시각을 마이크로초로 내린다. JVM 시계는 OS 에 따라 나노초까지 주는데(Linux) MySQL 은 datetime(6) 에 넣으며
     * 마이크로초 아래를 반올림한다. 그대로 두면 저장하고 돌려준 값과 DB 에 들어간 값이 어긋난다 — 같은 행인데
     * 메모리와 DB 의 시각이 다르고, macOS(마이크로초 시계)에서는 드러나지 않는다.
     */
    public static Clock atStorageResolution(Clock base) {
        return Clock.tick(base, STORAGE_RESOLUTION);
    }
}
