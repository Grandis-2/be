package com.grandis.nova.catalog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * common:types 의 BaseEntity 가 created_at · updated_at 을 채우려면 Auditing 이 켜져 있어야 한다.
 * 안 켜면 값이 null 로 들어가고 NOT NULL 제약에 걸려서야 드러난다.
 *
 * Auditing 시각도 {@link Clock} 에서 가져온다. 아웃박스 리스 기한처럼 코드가 직접 찍는 시각과
 * 같은 시계를 써야 한 트랜잭션 안의 시각들이 서로 어긋나지 않는다.
 *
 * common:security 의 JwtConfiguration 도 조건 없이 {@code clock} 이라는 이름의 빈을 둔다(실측: 같은 이름이면 기동 실패). 그래서 이 시계는
 * 이름을 달리하고 {@code @Primary} 로 둔다 — {@link Clock} 을 타입으로 주입받는 모든 자리(등록 · 목록 · 상세 · Auditing · JWT 파싱)가
 * 마이크로초로 내린 이 시계를 쓴다. JWT 의 iat · exp 는 초 단위라 내림의 영향이 없다.
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
     * 마이크로초 아래를 반올림한다(실측: …00.999999600Z → 00:00:01.000000). 그대로 두면 저장하고 돌려준 값과 DB 에 들어간 값이
     * 어긋난다 — 같은 행인데 메모리와 DB 의 시각이 다르고, macOS(마이크로초 시계)에서는 드러나지 않는다.
     */
    public static Clock atStorageResolution(Clock base) {
        return Clock.tick(base, STORAGE_RESOLUTION);
    }
}
