package com.grandis.nova.waitingroom.support;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

/** JVM 하나에 Redis 하나(CI 와 같은 redis:8). 테스트마다 FLUSHALL 로 비우고 쓴다. */
public final class RedisContainer {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8").withExposedPorts(6379);
    private static LettuceConnectionFactory factory;

    private RedisContainer() {
    }

    public static synchronized ReactiveStringRedisTemplate template() {
        if (factory == null) {
            REDIS.start();
            factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
            factory.afterPropertiesSet();
            factory.start();
        }
        return new ReactiveStringRedisTemplate(factory);
    }

    public static ReactiveStringRedisTemplate fresh() {
        ReactiveStringRedisTemplate template = template();
        template.execute(connection -> connection.serverCommands().flushAll()).blockLast();
        return template;
    }

    public static String host() {
        template();
        return REDIS.getHost();
    }

    public static int port() {
        template();
        return REDIS.getMappedPort(6379);
    }
}
