package com.grandis.nova.catalog.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;

/**
 * 토큰 폐기 표식(sid · not-before)을 읽는 Redis. JVM 에 하나뿐인 싱글턴 컨테이너에 접속 정보만 잇는다(preorder 선례).
 * 실측: GenericContainer 에 @ServiceConnection 을 달면 Boot 4 가 Redis 접속 정보 팩터리를 못 찾아 컨텍스트가 안 뜬다
 * (ConnectionDetailsNotFoundException) — 속성으로 잇는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisContainerConfig {

    static final String IMAGE = "redis:8";
    static final int PORT = 6379;

    private static final GenericContainer<?> REDIS = start();

    private static GenericContainer<?> start() {
        GenericContainer<?> container = new GenericContainer<>(IMAGE).withExposedPorts(PORT);
        container.start();
        return container;
    }

    @Bean
    DynamicPropertyRegistrar redisProperties() {
        return registry -> {
            registry.add("spring.data.redis.host", REDIS::getHost);
            registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(PORT));
        };
    }
}
