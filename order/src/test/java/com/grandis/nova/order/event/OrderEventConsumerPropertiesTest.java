package com.grandis.nova.order.event;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 설정 키는 예전(nova.sqs.consumer.*) 그대로이고, 기본값 묶음이 SQS 범위 안이며, 꺼 둔 환경에서도 범위 밖의 값은 바인딩에서 막힌다. */
class OrderEventConsumerPropertiesTest {

    final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Properties.class);

    @Test
    void 기본값은_꺼져_있고_order_events_를_받는_유효한_설정이다() {
        runner.run(ctx -> {
            OrderEventConsumerProperties properties = ctx.getBean(OrderEventConsumerProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.toSettings().queue()).isEqualTo("order-events");
            assertThat(properties.toSettings().waitSeconds()).isEqualTo(20);
            assertThat(properties.toSettings().visibility()).isEqualTo(Duration.ofMinutes(5));
        });
    }

    @Test
    void 꺼_둔_환경에서도_범위_밖의_값은_받지_않는다() {
        runner.withPropertyValues("nova.sqs.consumer.max-messages=20").run(ctx ->
                assertThat(ctx).getFailure().rootCause().hasMessageContaining("max-messages"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OrderEventConsumerProperties.class)
    static class Properties {
    }
}
