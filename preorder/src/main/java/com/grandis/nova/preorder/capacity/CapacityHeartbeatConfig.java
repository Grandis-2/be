package com.grandis.nova.preorder.capacity;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CapacityHeartbeatProperties.class)
class CapacityHeartbeatConfig {

    @Bean
    @ConditionalOnBooleanProperty(name = "nova.capacity.heartbeat.enabled", matchIfMissing = true)
    CapacityHeartbeat capacityHeartbeat(StringRedisTemplate redis, CapacityHeartbeatProperties properties,
                                        MeterRegistry meterRegistry) {
        return new CapacityHeartbeat(redis, properties, meterRegistry);
    }
}
