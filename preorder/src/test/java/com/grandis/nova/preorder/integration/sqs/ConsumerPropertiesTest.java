package com.grandis.nova.preorder.integration.sqs;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설정 키는 예전(nova.sqs.consumer.* · nova.sqs.dead-letter.*) 그대로이고 기본값도 같다. 범위 검증은 QueuePollerSettings 가 하고,
 * 꺼 둔 환경에서도 바인딩할 때 막힌다(범위 표 전체는 common:sqs 의 시험이 본다).
 */
class ConsumerPropertiesTest {

    final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Properties.class);

    @Test
    void 기본값은_꺼져_있고_preorder_events_와_그_DLQ_를_받는다() {
        runner.run(ctx -> {
            PreorderEventConsumerProperties consumer = ctx.getBean(PreorderEventConsumerProperties.class);
            DeadLetterConsumerProperties deadLetter = ctx.getBean(DeadLetterConsumerProperties.class);

            assertThat(consumer.enabled()).isFalse();
            assertThat(consumer.toSettings().queue()).isEqualTo("preorder-events");
            assertThat(consumer.toSettings().visibility()).isEqualTo(Duration.ofMinutes(5));
            assertThat(consumer.toSettings().backoffBase()).isEqualTo(Duration.ofSeconds(5));
            assertThat(consumer.toDeferSettings().firstDelay()).isEqualTo(Duration.ofSeconds(10));
            assertThat(consumer.toDeferSettings().maxDelay()).isEqualTo(Duration.ofMinutes(15));
            assertThat(consumer.toDeferSettings().alertAfter()).isEqualTo(Duration.ofHours(1));
            assertThat(deadLetter.enabled()).isFalse();
            assertThat(deadLetter.toSettings().queue()).isEqualTo("preorder-events-dlq");
            assertThat(deadLetter.toSettings().concurrency()).isEqualTo(1);
            assertThat(deadLetter.toSettings().maxMessages()).isEqualTo(10);
            assertThat(deadLetter.toSettings().visibility()).isEqualTo(Duration.ofMinutes(1));
        });
    }

    @Test
    void 꺼_둔_환경에서도_소비기_설정이_범위_밖이면_받지_않는다() {
        runner.withPropertyValues("nova.sqs.consumer.max-messages=20").run(ctx ->
                assertThat(ctx).getFailure().rootCause().hasMessageContaining("max-messages"));
    }

    @Test
    void 꺼_둔_환경에서도_보류_설정이_범위_밖이면_받지_않는다() {
        runner.withPropertyValues("nova.sqs.consumer.defer-max-delay=16m").run(ctx ->
                assertThat(ctx).getFailure().rootCause().hasMessageContaining("max"));
    }

    @Test
    void 꺼_둔_환경에서도_DLQ_소비기_설정이_범위_밖이면_받지_않는다() {
        runner.withPropertyValues("nova.sqs.dead-letter.wait-seconds=21").run(ctx ->
                assertThat(ctx).getFailure().rootCause().hasMessageContaining("wait-seconds"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({PreorderEventConsumerProperties.class, DeadLetterConsumerProperties.class})
    static class Properties {
    }
}
