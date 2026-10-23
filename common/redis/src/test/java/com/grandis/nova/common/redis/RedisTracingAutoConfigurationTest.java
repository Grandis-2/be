package com.grandis.nova.common.redis;

import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.tracing.MicrometerTracing;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.observation.LettuceObservationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Spring Boot 의 Lettuce 관측이 끼운 추적기를 감싼 추적기가 이긴다. 관측이 없으면 둘 다 끼우지 않는다. */
class RedisTracingAutoConfigurationTest {

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues("nova.redis.warmup.enabled=false")
            .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class,
                    LettuceObservationAutoConfiguration.class, RedisTracingAutoConfiguration.class));

    @Test
    void 관측이_있으면_스팬을_남기는_추적기가_끼워진다() {
        runner.withBean(ObservationRegistry.class, ObservationRegistry::create)
                .run(context -> assertThat(context.getBean(ClientResources.class).tracing())
                        .isInstanceOf(RedisSpanTracing.class));
    }

    @Test
    void 관측이_없으면_끼우지_않는다() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(RedisTracingAutoConfiguration.class);
            assertThat(context.getBean(ClientResources.class).tracing())
                    .isNotInstanceOf(RedisSpanTracing.class)
                    .isNotInstanceOf(MicrometerTracing.class);
        });
    }
}
