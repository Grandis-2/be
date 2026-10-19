package com.grandis.nova.common.redis;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Redis 를 쓰는 서비스가 기동 때 공유 연결을 미리 열게 한다({@link RedisWarmup}). 연결 팩토리가 하나로 정해질 때만 켜진다 —
 * 둘 이상인데 @Primary 가 없으면 주입이 실패해 기동이 멈추므로 물러난다. {@code nova.redis.warmup.enabled=false} 로 끈다.
 *
 * 자동설정이라 서비스의 컴포넌트 스캔(com.grandis.nova)에서는 빠진다. 공통 클래스에는 @Component 를 붙이지 않는다.
 */
@AutoConfiguration(after = DataRedisAutoConfiguration.class)
@ConditionalOnSingleCandidate(RedisConnectionFactory.class)
@ConditionalOnProperty(prefix = "nova.redis.warmup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisWarmupAutoConfiguration {

    @Bean
    RedisWarmup redisWarmup(RedisConnectionFactory connections) {
        return new RedisWarmup(connections, RedisWarmup.BUDGET);
    }
}
