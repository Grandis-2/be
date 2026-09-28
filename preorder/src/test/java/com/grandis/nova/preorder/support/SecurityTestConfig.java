package com.grandis.nova.preorder.support;

import com.grandis.nova.preorder.support.containers.RedisTestContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;

/** 토큰 검증 공개키(AccessTokens 의 키)와 폐기 확인용 싱글턴 Redis 를 잇는다. */
@TestConfiguration(proxyBeanMethods = false)
public class SecurityTestConfig {

    @Bean
    DynamicPropertyRegistrar securityProperties() {
        GenericContainer<?> redis = RedisTestContainer.get();
        return registry -> {
            registry.add("jwt.public-keys." + AccessTokens.KEY_ID, AccessTokens::publicKeyPem);
            registry.add("spring.data.redis.host", redis::getHost);
            registry.add("spring.data.redis.port", () -> redis.getMappedPort(RedisTestContainer.PORT));
        };
    }
}
