package com.grandis.nova.order.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MySQL 8.4 + Flyway 마이그레이션 위에서 도는 통합 테스트.
 * application.yml 은 커밋하지 않으므로 운영과 같아야 하는 값(특히 격리 수준)을 여기서 준다.
 *
 * 아웃박스 발행은 로그 전송기로 한다(전송 구현이 없으면 기동하지 않는다). 릴레이는 테스트가 직접 부른다 —
 * 주기 실행이 끼어들면 테스트가 부른 릴레이와 잠그는 행이 겹친다.
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
        "nova.outbox.transport=log",
        "nova.outbox.relay-interval=1h"
})
@Import(MySqlContainerConfig.class)
public @interface OrderIntegrationTest {
}
