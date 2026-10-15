package com.grandis.nova.member.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/** 키는 order 와 같은 nova.sqs.consumer.* 이고, 기본값은 꺼진 채 notification 을 받으며, 예시 설정이 그대로 바인딩된다. */
@DisplayName("알림 소비기 설정")
class NotificationConsumerPropertiesTest {

    final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class);

    @Test
    @DisplayName("기본값은 꺼져 있고 notification 을 받는 유효한 설정이다")
    void defaults() {
        runner.run(ctx -> {
            NotificationConsumerProperties properties = ctx.getBean(NotificationConsumerProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.toSettings().queue()).isEqualTo("notification");
        });
    }

    @Test
    @DisplayName("꺼 둔 환경에서도 범위 밖의 값은 받지 않는다")
    void rejectsOutOfRangeEvenWhenDisabled() {
        runner.withPropertyValues("nova.sqs.consumer.wait-seconds=21").run(ctx ->
                assertThat(ctx).getFailure().rootCause().hasMessageContaining("wait-seconds"));
    }

    @Test
    @DisplayName("application.yml.example 의 소비기 칸이 그대로 바인딩된다")
    void exampleBinds() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml.example"));
        Properties example = yaml.getObject();
        assertThat(example).isNotNull();
        // 값이 Boolean · Integer 로도 들어 있어 stringPropertyNames() 로는 그 칸이 빠진다
        String[] consumer = example.entrySet().stream()
                .filter(entry -> entry.getKey().toString().startsWith("nova.sqs.consumer."))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);

        runner.withPropertyValues(consumer).run(ctx -> {
            NotificationConsumerProperties properties = ctx.getBean(NotificationConsumerProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.toSettings().queue()).isEqualTo("notification");
            assertThat(properties.toSettings().concurrency()).isEqualTo(1);
            assertThat(properties.toSettings().waitSeconds()).isEqualTo(20);
            assertThat(properties.toSettings().visibility()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.toSettings().backoffBase()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.toSettings().backoffMax()).isEqualTo(Duration.ofMinutes(5));
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationConsumerProperties.class)
    static class PropertiesConfig {
    }
}
