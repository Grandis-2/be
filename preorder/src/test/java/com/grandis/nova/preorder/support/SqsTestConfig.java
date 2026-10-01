package com.grandis.nova.preorder.support;

import com.grandis.nova.preorder.support.containers.FlociTestContainer;
import io.floci.testcontainers.FlociContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import software.amazon.awssdk.services.sqs.SqsClient;

/** 컨텍스트 밖의 싱글턴 Floci 에 SQS 주소 · 자격 증명을 잇고, 큐를 다루는 테스트 도우미를 둔다. */
@TestConfiguration(proxyBeanMethods = false)
public class SqsTestConfig {

    @Bean
    DynamicPropertyRegistrar flociProperties() {
        FlociContainer floci = FlociTestContainer.get();
        return registry -> {
            registry.add("nova.sqs.endpoint", floci::getEndpoint);
            registry.add("nova.sqs.access-key", floci::getAccessKey);
            registry.add("nova.sqs.secret-key", floci::getSecretKey);
        };
    }

    @Bean
    TestQueues testQueues(SqsClient sqs) {
        return new TestQueues(sqs);
    }
}
