package com.grandis.nova.common.jpa;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.util.Optional;

/**
 * 서비스의 시계와 JPA Auditing. 코드가 찍는 시각과 Auditing(created_at · updated_at) 시각이 모두 저장 시계 하나에서 온다 —
 * Auditing 을 안 켜면 NOT NULL 위반으로만 드러나고, 시계가 둘이면 코드가 찍은 시각과 Auditing 시각이 어긋난다.
 *
 * 빈 이름이 clock 이 아니라 storageClock 인 것은 common:security 의 JwtConfiguration 이 조건 없이 clock(systemUTC) 빈을 두기 때문이다 —
 * 같은 이름이면 기동이 실패한다. 그래서 두 빈이 함께 있고, @Primary 가 모든 주입 지점(JWT 키 링 포함)을 이 시계로 모은다.
 * {@code @ConditionalOnMissingBean(Clock.class)} 를 달지 않는다: HTTP 서비스에는 그 clock 이 늘 있어 저장 시계가 영영 안 생긴다.
 * 테스트에서 바꿀 때는 이름으로 덮는다({@code @TestBean(name = "storageClock")}).
 *
 * 자동설정이라 서비스의 컴포넌트 스캔(com.grandis.nova)에서는 빠진다(AutoConfigurationExcludeFilter). 쓰는 앱이
 * {@code @EnableJpaAuditing} 을 또 켜면 Auditing 이 두 번 등록돼 기동이 실패한다.
 */
@AutoConfiguration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingAutoConfiguration {

    @Bean
    @Primary
    Clock storageClock() {
        return StorageClock.atStorageResolution(Clock.systemUTC());
    }

    @Bean
    DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant());
    }
}
