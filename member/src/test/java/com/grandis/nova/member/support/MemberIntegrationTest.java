package com.grandis.nova.member.support;

import com.grandis.nova.member.MemberApplication;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 실제 MySQL · Redis 위에서 도는 통합 시험. 인프라를 쓰는 시험은 이것만 붙인다.
 *
 * 운영 설정(application.yml)은 저장소에 없으므로(*.yml 은 커밋하지 않는다) 시험이 기대는 값을 여기서 준다.
 * 특히 격리 수준 — 빠뜨리면 MySQL 기본값 REPEATABLE READ 로 돌아 운영과 다른 잠금 동작을 검증하게 된다.
 *
 * 스키마는 {@link Containers} 가 마이그레이션으로 이미 만들어 두므로 스프링의 Flyway 는 끈다.
 * 비밀값·접속 정보는 {@link MemberTestContext} 가 넣는다.
 *
 * 특정 값이 달라야 하는 시험(예: 쿠키 Secure 를 켜고 보는 것)은 클래스에 `@TestPropertySource` 로 그 키만 덮는다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(classes = MemberApplication.class, properties = {
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
        "spring.flyway.enabled=false",
        "jwt.issuer=nova-test",
        "jwt.access-token-validity=30m",
        "jwt.refresh-token-validity=14d",
        "kakao.client-id=cid",
        "kakao.client-secret=csecret",
        "kakao.token-uri=https://kauth.kakao.com/oauth/token",
        "kakao.user-info-uri=https://kapi.kakao.com/v2/user/me",
        "kakao.allowed-redirect-uris=http://localhost:3000/login/kakao/callback",
        "auth.refresh.allowed-origins=http://localhost:3000",
        "admin.username=admin",
        "auth.cookie.secure=false",
        // 기본값(60s)으로는 Redis 가 죽은 갈래가 시험을 멈춰 세운다. 예시 설정과 같은 값으로 빨리 실패시킨다.
        "spring.data.redis.timeout=300ms",
        "spring.data.redis.connect-timeout=200ms"
})
@Import(MemberTestContext.class)
public @interface MemberIntegrationTest {
}
