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

/** 기본값이 운영값이고(인프라에 적지 않는다), 예시 설정이 그 값 그대로 바인딩되며, 0 이하는 기동을 막는다. */
@DisplayName("알림 실시간 연결 설정")
class NotificationStreamPropertiesTest {

    static final NotificationStreamProperties DEFAULTS =
            new NotificationStreamProperties(Duration.ofSeconds(25), Duration.ofMinutes(30), 5, 5000, Duration.ofSeconds(2));

    final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class);

    @Test
    @DisplayName("기본값 — 주기 신호 25초 · 최대 수명 30분 · 회원당 5 · 인스턴스당 5000 · 2초마다 세기")
    void defaults() {
        runner.run(ctx -> assertThat(ctx.getBean(NotificationStreamProperties.class))
                .isEqualTo(DEFAULTS));
    }

    @Test
    @DisplayName("application.yml.example 의 값이 기본값과 같게 바인딩된다")
    void exampleBinds() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml.example"));
        Properties example = yaml.getObject();
        assertThat(example).isNotNull();
        String[] stream = example.entrySet().stream()
                .filter(entry -> entry.getKey().toString().startsWith("member.notification.stream."))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
        assertThat(stream).hasSize(5);

        runner.withPropertyValues(stream).run(ctx -> assertThat(ctx.getBean(NotificationStreamProperties.class))
                .isEqualTo(DEFAULTS));
    }

    @Test
    @DisplayName("간격 · 수명 · 상한이 0 이하면 기동이 실패한다")
    void rejectsNonPositive() {
        for (String bad : new String[] {"heartbeat=0s", "max-lifetime=-1s", "max-per-customer=0", "max-connections=0", "tail-interval=0s"}) {
            runner.withPropertyValues("member.notification.stream." + bad).run(ctx ->
                    assertThat(ctx).getFailure().rootCause().isInstanceOf(IllegalArgumentException.class));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationStreamProperties.class)
    static class PropertiesConfig {
    }
}
