package com.grandis.nova.order.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MySQL 8.4 + Flyway 마이그레이션 위에서 도는 통합 테스트. application.yml 은 커밋하지 않으므로 운영과 같아야 하는 값을 여기서 준다.
 *
 * - DB: 격리 수준 READ COMMITTED 등 운영 값 그대로.
 * - 아웃박스: 로그 전송기(명시 허용 포함 — 전송 구현이 없거나 허용이 없으면 기동하지 않는다). 릴레이는 테스트가 직접 부른다 —
 *   주기 실행이 끼어들면 테스트가 부른 릴레이와 잠그는 행이 겹친다.
 * - 인증: JWT 서명 키는 {@link TestJwt} 가 실행마다 만든다. Redis 는 띄우지 않는다 — 인증 흉내({@link TestAuth})는 필터가
 *   토큰을 읽지 않고, 진짜 토큰을 쓰는 테스트는 RevocationChecker 를 대역으로 바꾼다. 닫는 경로 목록은 application.yml.example 과 같다.
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
        "nova.outbox.log-transport-allowed=true",
        "nova.outbox.relay-interval=1h",
        // 결제 기한 만료도 시험이 직접 부른다(CartOrderExpiry.expireDue) — 주기 실행이 다른 시험의 주문을 취소하지 않게
        "nova.cart-order.expiry-interval=1h",
        // 관리 포트는 코드 기본값이 고정 포트라, 실제 포트로 띄우는 컨텍스트끼리 겹치지 않게 빈 포트로 둔다
        "management.server.port=0",
        "jwt.issuer=nova-test",
        "jwt.access-token-validity=30m",
        "jwt.refresh-token-validity=14d",
        "auth.revocation-check.fail-closed-paths[0]=/api/v1/admin/**",
        "auth.revocation-check.fail-closed-paths[1]=/api/v1/session/refresh",
        "auth.revocation-check.fail-closed-paths[2]=/api/v1/me/**",
        "auth.revocation-check.fail-closed-paths[3]=/api/v1/orders/*/payment-attempts/**",
        "auth.revocation-check.fail-closed-paths[4]=POST /api/v1/orders/*/cancel",
        "auth.revocation-check.fail-closed-paths[5]=PATCH /api/v1/orders/*/shipping-address"
})
@Import({MySqlContainerConfig.class, TestJwt.class})
public @interface OrderIntegrationTest {

    /** 기본은 MockMvc(MOCK). 내장 톰캣을 실제로 띄워 HTTP 로 쳐야 하는 시험만 RANDOM_PORT 로 바꾼다. */
    @AliasFor(annotation = SpringBootTest.class)
    SpringBootTest.WebEnvironment webEnvironment() default SpringBootTest.WebEnvironment.MOCK;
}
