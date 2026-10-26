package com.grandis.nova.member.adminalert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** 관리자 알림 설정 셋 — 기본값이 운영값이고(인프라에 적지 않는다), 예시 설정이 그 값 그대로 바인딩되며, 범위 밖 값은 기동을 막는다. */
@DisplayName("관리자 알림 설정 — 실시간 연결 · 소비기 · 정리")
class AdminAlertPropertiesTest {

    @Nested
    @DisplayName("실시간 연결(member.admin-alert.stream)")
    class Stream {

        static final AdminAlertStreamProperties DEFAULTS = new AdminAlertStreamProperties(Duration.ofSeconds(25), Duration.ofMinutes(30),
                20, 100, 1000, 100, Duration.ofSeconds(2), Duration.ofSeconds(10));

        final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(StreamConfig.class);

        @Test
        @DisplayName("기본값 — 주기 신호 25초 · 최대 수명 30분 · 계정당 20 · 인스턴스당 100 · 쌓기 1000 · resync 100 · 2초마다 읽기 · 10초 겹침")
        void defaults() {
            runner.run(ctx -> assertThat(ctx.getBean(AdminAlertStreamProperties.class)).isEqualTo(DEFAULTS));
        }

        @Test
        @DisplayName("application.yml.example 의 값이 기본값과 같게 바인딩된다")
        void exampleBinds() {
            String[] values = example("member.admin-alert.stream.");
            assertThat(values).hasSize(8);
            runner.withPropertyValues(values).run(ctx -> assertThat(ctx.getBean(AdminAlertStreamProperties.class)).isEqualTo(DEFAULTS));
        }

        @Test
        @DisplayName("간격 · 수명 · 상한이 0 이하이거나 resync 기준이 쌓기 상한 이상이면 기동이 실패한다")
        void rejectsNonPositive() {
            for (String bad : new String[] {"heartbeat=0s", "max-lifetime=-1s", "max-per-principal=0", "max-connections=0", "max-pending=0",
                    "resync-threshold=0", "resync-threshold=1000", "tail-interval=0s", "overlap=0s"}) {
                runner.withPropertyValues("member.admin-alert.stream." + bad).run(ctx ->
                        assertThat(ctx).getFailure().rootCause().isInstanceOf(IllegalArgumentException.class));
            }
        }
    }

    @Nested
    @DisplayName("소비기(member.admin-alert.consumer)")
    class Consumer {

        final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(ConsumerConfig.class);

        @Test
        @DisplayName("기본값은 두 큐(admin-alert · admin-alert-grafana) · 한 번에 5건 · 가시성 3분 — 운영 notification 과 같은 값")
        void defaults() {
            runner.run(ctx -> {
                AdminAlertConsumerProperties properties = ctx.getBean(AdminAlertConsumerProperties.class);
                assertThat(properties.toSettings(properties.eventQueue()).queue()).isEqualTo("admin-alert");
                assertThat(properties.toSettings(properties.grafanaQueue()).queue()).isEqualTo("admin-alert-grafana");
                assertThat(properties.maxMessages()).isEqualTo(5);
                assertThat(properties.visibility()).isEqualTo(Duration.ofMinutes(3));
            });
        }

        @Test
        @DisplayName("범위 밖의 값은 받지 않는다")
        void rejectsOutOfRange() {
            runner.withPropertyValues("member.admin-alert.consumer.wait-seconds=21").run(ctx ->
                    assertThat(ctx).getFailure().rootCause().hasMessageContaining("wait-seconds"));
        }

        @Test
        @DisplayName("application.yml.example 의 소비기 칸이 기본값과 같게 바인딩된다")
        void exampleBinds() {
            ApplicationContextRunner defaults = new ApplicationContextRunner().withUserConfiguration(ConsumerConfig.class);
            defaults.run(expected -> runner.withPropertyValues(example("member.admin-alert.consumer.")).run(ctx ->
                    assertThat(ctx.getBean(AdminAlertConsumerProperties.class))
                            .isEqualTo(expected.getBean(AdminAlertConsumerProperties.class))));
        }
    }

    @Nested
    @DisplayName("정리(member.admin-alert.cleanup)")
    class Cleanup {

        final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(CleanupConfig.class);

        @Test
        @DisplayName("기본값 — 해소 뒤 30일, application.yml.example 도 같은 값으로 바인딩된다")
        void defaultsAndExample() {
            runner.run(expected -> {
                assertThat(expected.getBean(AdminAlertCleanup.Settings.class).retention()).isEqualTo(Duration.ofDays(30));
                runner.withPropertyValues(example("member.admin-alert.cleanup.")).run(ctx ->
                        assertThat(ctx.getBean(AdminAlertCleanup.Settings.class)).isEqualTo(expected.getBean(AdminAlertCleanup.Settings.class)));
            });
        }

        @Test
        @DisplayName("기간 · 묶음 · 횟수가 0 이하면 기동이 실패한다")
        void rejectsNonPositive() {
            for (String bad : new String[] {"retention=0s", "batch-size=0", "max-batches-per-run=0", "refill-window=0s", "refill-limit=0"}) {
                runner.withPropertyValues("member.admin-alert.cleanup." + bad).run(ctx ->
                        assertThat(ctx).getFailure().rootCause().isInstanceOf(IllegalArgumentException.class));
            }
        }
    }

    /** 예시 설정에서 그 접두어의 칸만(cron 처럼 Settings 에 없는 칸은 빼고). 값이 Boolean · Integer 로도 들어 있어 entrySet 으로 읽는다. */
    static String[] example(String prefix) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml.example"));
        Properties example = yaml.getObject();
        assertThat(example).isNotNull();
        return example.entrySet().stream()
                .filter(entry -> entry.getKey().toString().startsWith(prefix) && !entry.getKey().toString().endsWith(".cron"))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AdminAlertStreamProperties.class)
    static class StreamConfig {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AdminAlertConsumerProperties.class)
    static class ConsumerConfig {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AdminAlertCleanup.Settings.class)
    static class CleanupConfig {
    }
}
