package com.grandis.nova.member.support;

import com.grandis.nova.common.sqs.testing.SqsTestConfig;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link MemberIntegrationTest} 에 실제 SQS 프로토콜(Floci)을 더한 통합 시험. 소비기를 켠다(application-sqs-test.properties).
 * 큐는 TestQueues 로 넣고 꺼내 본다. Floci 컨테이너 · 큐 도우미는 common:sqs 의 테스트 픽스처다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@MemberIntegrationTest
@ActiveProfiles("sqs-test")
@Import(SqsTestConfig.class)
public @interface SqsIntegrationTest {
}
