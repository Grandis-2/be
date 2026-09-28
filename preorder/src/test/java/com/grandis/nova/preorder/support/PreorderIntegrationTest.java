package com.grandis.nova.preorder.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MySQL 8.4 위에서 도는 통합 테스트. 공통 설정은 application-test.properties, DB · Redis 는 JVM 에 하나뿐인
 * 싱글턴 컨테이너다. 요청 인증은 AccessTokens 의 실제 토큰으로 한다. 발행은 로그로만 하고 SQS 소비기는 끈다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "nova.admission-ticket.secret=" + PreorderIntegrationTest.ADMISSION_TICKET_SECRET)
@ActiveProfiles("test")
@Import({MySqlTestConfig.class, SecurityTestConfig.class})
public @interface PreorderIntegrationTest {

    /** 테스트 전용 입장권 비밀. 테스트 발급기(AdmissionTickets)가 같은 값으로 서명한다. */
    String ADMISSION_TICKET_SECRET = "nova-test-current-secret-0123456789";
}
