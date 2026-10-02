package com.grandis.nova.catalog.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MySQL 8.4 위에서 도는 통합 테스트. DB 동작을 검증하는 테스트는 이것을 붙인다.
 *
 * 운영 설정(application.yml)은 저장소에 없으므로(*.yml 은 커밋하지 않음) 테스트가 기대는 값을 여기서 준다.
 * 특히 격리 수준 — 빠뜨리면 MySQL 기본값 REPEATABLE READ 로 돌아 운영과 다른 잠금 동작을 검증하게 된다.
 *
 * 스키마는 flyway-project/migrations 를 Flyway 로 적용해 만든다. 운영 DB 에 쓰는 것과 같은 파일이라
 * 엔티티의 ddl-auto: validate 가 실제 배포 스키마와 대조된다. Flyway 설정은 flyway-project/flyway.toml 과 맞춘다.
 *
 * 인증은 common:security 그대로 돈다 — 폐기 표식은 Redis 컨테이너, 토큰 검증 공개키는 {@link AccessTokens} 의 키(SecurityTestConfig).
 * 요청 인증은 실제 토큰(AccessTokens)으로도, MockMvc 의 user() 로도 만들 수 있다(필터는 이미 인증된 요청은 건드리지 않는다).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
        "spring.flyway.locations=filesystem:${nova.migrations-path}",
        "spring.flyway.default-schema=shop",
        "spring.flyway.schemas=shop",
        "spring.flyway.create-schemas=false",
        "spring.flyway.clean-disabled=true",
        "spring.flyway.validate-migration-naming=true",
        "spring.threads.virtual.enabled=true",
        // 검증만 한다(개인키 없음). 발급자 · 유효기간은 member 와 같은 값 — 발급하지 않아도 필수 값이다
        "jwt.issuer=" + AccessTokens.ISSUER,
        "jwt.access-token-validity=30m",
        "jwt.refresh-token-validity=14d",
        // Redis 가 죽은 갈래를 시험할 때 기본값(60s)이면 시험이 멈춰 선다. 예시 설정과 같은 값
        "spring.data.redis.timeout=300ms",
        "spring.data.redis.connect-timeout=200ms",
        // 아웃박스는 로그로 보낸다(큐 없이 흐름이 끝까지 돈다). SQS 로 보내는 시험은 OutboxSqsFlowTest 가 따로 켠다.
        // 릴레이는 시험 도중 스스로 돌지 않게 주기를 길게 둔다 — 릴레이를 보는 시험은 relay() 를 직접 부른다
        "nova.outbox.transport=log",
        "nova.outbox.relay-interval=1h"
})
@Import({MySqlContainerConfig.class, RedisContainerConfig.class, SecurityTestConfig.class})
public @interface CatalogIntegrationTest {
}
