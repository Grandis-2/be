package com.grandis.nova.payment.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MySQL 8.4 + Flyway 마이그레이션 위에서 도는 통합 테스트. application.yml 은 커밋하지 않으므로 운영과 같아야 하는 값을 여기서 준다.
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
        // 토스 가맹점 자격 증명 · 주소 · 시간 예산은 필수라(없으면 기동 실패) 운영 값으로 채운다.
        // 통합 테스트는 토스를 부르지 않는다(client.toss 테스트가 따로 본다)
        "nova.payment.toss.secret-key=integration-test-unused",
        "spring.http.serviceclient.toss.base-url=https://api.tosspayments.com",
        "spring.http.serviceclient.toss.connect-timeout=3s",
        "spring.http.serviceclient.toss.read-timeout=60s",
        "spring.http.serviceclient.toss.redirects=dont-follow"
})
@Import(MySqlContainerConfig.class)
public @interface PaymentIntegrationTest {
}
