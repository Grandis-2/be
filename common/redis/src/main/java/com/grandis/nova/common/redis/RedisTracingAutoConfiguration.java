package com.grandis.nova.common.redis;

import io.lettuce.core.tracing.MicrometerTracing;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.data.redis.autoconfigure.ClientResourcesBuilderCustomizer;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.observation.LettuceObservationAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Redis 명령을 트레이스에 스팬으로 남긴다({@link RedisSpanTracing}). Spring Boot 의 Lettuce 관측(lettuce_seconds 지표)을 그대로 두고 감싼다.
 * 그 자동설정의 추적기 바꿔 끼우기 뒤에 같은 방식으로 끼워야 이기므로 그 뒤에 둔다. 스팬은 OTel 에이전트가 있으면 에이전트로 나간다.
 */
@AutoConfiguration(after = LettuceObservationAutoConfiguration.class, before = DataRedisAutoConfiguration.class)
@ConditionalOnClass({MicrometerTracing.class, ObservationRegistry.class, GlobalOpenTelemetry.class})
@ConditionalOnBean(ObservationRegistry.class)
public class RedisTracingAutoConfiguration {

    static final String INSTRUMENTATION = "com.grandis.nova.common.redis";

    @Bean
    ClientResourcesBuilderCustomizer redisSpanTracing(ObservationRegistry observationRegistry) {
        Tracer tracer = GlobalOpenTelemetry.getTracer(INSTRUMENTATION);
        return builder -> builder.tracing(
                new RedisSpanTracing(new MicrometerTracing(observationRegistry, "Redis"), tracer::spanBuilder));
    }
}
