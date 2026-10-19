package com.grandis.nova.catalog.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

@DisplayName("catalog-events 소비기 설정 — 기본은 꺼져 있고, 꺼 둔 환경에서도 범위 밖의 값은 바인딩에서 막힌다")
class CatalogEventConsumerPropertiesTest {

    final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Properties.class);

    @Test
    @DisplayName("기본값은 꺼져 있고 catalog-events 를 받는 유효한 설정이다")
    void defaultsAreOffAndValid() {
        runner.run(ctx -> {
            CatalogEventConsumerProperties properties = ctx.getBean(CatalogEventConsumerProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.toSettings().queue()).isEqualTo("catalog-events");
            assertThat(properties.toSettings().waitSeconds()).isEqualTo(20);
            assertThat(properties.toSettings().visibility()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.toSettings().backoffBase()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    @DisplayName("꺼 둔 환경에서도 범위 밖의 값(max-messages 20)은 받지 않는다")
    void rejectsOutOfRangeEvenWhenOff() {
        runner.withPropertyValues("nova.sqs.consumer.max-messages=20").run(ctx ->
                assertThat(ctx).getFailure().rootCause().hasMessageContaining("max-messages"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CatalogEventConsumerProperties.class)
    static class Properties {
    }
}
