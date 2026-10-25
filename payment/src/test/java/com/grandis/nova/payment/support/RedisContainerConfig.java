package com.grandis.nova.payment.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;

/**
 * Redis 가 필요한 시험만 붙인다(일괄 되돌리기 진행). {@link PaymentIntegrationTest} 의 다른 시험은 Redis 없이 돈다 — 폐기 조회는 대역이다.
 * 이미지는 preorder 시험 · CI 와 같다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisContainerConfig {

    static final String IMAGE = "redis:8";

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(IMAGE).withExposedPorts(6379);
    }
}
