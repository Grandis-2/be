package com.grandis.nova.preorder.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link PreorderIntegrationTest} 에 실제 SQS 프로토콜(Floci)을 더한 통합 테스트. 발행은 SQS 로 하고 소비기를 켠다
 * (application-sqs-test.properties). 큐는 {@link TestQueues} 로 넣고 꺼내 본다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = "nova.admission-ticket.secret=" + PreorderIntegrationTest.ADMISSION_TICKET_SECRET)
@ActiveProfiles({"test", "sqs-test"})
@Import({MySqlTestConfig.class, SqsTestConfig.class})
public @interface SqsIntegrationTest {
}
