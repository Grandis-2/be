package com.grandis.nova.preorder.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** 큐 연동이 없는 통합 테스트의 DLQ 되돌리기 대역. 모든 {@link PreorderIntegrationTest} 가 같이 써 컨텍스트를 늘리지 않는다. */
@TestConfiguration(proxyBeanMethods = false)
public class DeadLetterTestConfig {

    @Bean
    RecordingDeadLetterRedriver recordingDeadLetterRedriver() {
        return new RecordingDeadLetterRedriver();
    }
}
