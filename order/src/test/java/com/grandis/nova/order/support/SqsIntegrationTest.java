package com.grandis.nova.order.support;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link OrderIntegrationTest} 에 실제 SQS 프로토콜(Floci)을 더한 통합 테스트. 소비기를 켠다
 * (application-sqs-test.properties). 큐는 {@link TestQueues} 로 넣고 꺼내 본다.
 *
 * common:outbox 이전 시: preorder support.SqsIntegrationTest 와 같은 역할이다. 이 모듈은 DB 설정을 OrderIntegrationTest 에
 * 두므로 그것을 메타 애너테이션으로 잇는다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@OrderIntegrationTest
@ActiveProfiles("sqs-test")
@Import(SqsTestConfig.class)
public @interface SqsIntegrationTest {
}
