package com.grandis.nova.preorder.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.util.Optional;

/**
 * common:types 의 BaseEntity 가 created_at · updated_at 을 채우려면 Auditing 이 켜져 있어야 한다.
 * 안 켜면 값이 null 로 들어가고 NOT NULL 제약에 걸려서야 드러난다.
 *
 * Auditing 시각도 {@link Clock} 에서 가져온다. 이력·결제 기한처럼 코드가 직접 찍는 시각과
 * 같은 시계를 써야 한 트랜잭션 안의 시각들이 서로 어긋나지 않는다. Clock 빈은 common:security 가 둔다(UTC).
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    @Bean
    DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant());
    }
}
