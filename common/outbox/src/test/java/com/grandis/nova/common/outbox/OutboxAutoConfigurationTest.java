package com.grandis.nova.common.outbox;

import com.grandis.nova.common.outbox.support.MySqlTestContainer;
import com.grandis.nova.common.outbox.support.TestOutbox;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자동설정이 서비스 컨텍스트에서 어떻게 엮이는가. 서비스가 주는 것(OutboxDefinition · transport)이 빠지거나 표가 어긋나면
 * 기동이 멈춰야 하고, 서비스의 스케줄러 · 실행기 · 지표 구성은 바꾸지 않아야 한다.
 */
class OutboxAutoConfigurationTest {

    static DataSource dataSource;

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
            .withBean(DataSource.class, () -> dataSource)
            .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
            .withBean(JsonMapper.class, JsonMapper::new)
            .withPropertyValues("nova.outbox.transport=log", "nova.outbox.log-transport-allowed=true",
                    "nova.outbox.relay-interval=1h");

    @BeforeAll
    static void createTables() {
        MySQLContainer mysql = MySqlTestContainer.get();
        dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("outbox-test-schema.sql")).execute(dataSource);
    }

    @Test
    void 서비스가_표를_주면_뜬다() {
        runner.withBean(OutboxDefinition.class, () -> definition(TestOutbox.TABLE)).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(OutboxWriter.class);
            assertThat(ctx.getBean(OutboxRelayScheduler.class).isRunning()).isTrue();
        });
    }

    @Test
    void 표를_주지_않으면_기동이_실패한다() {
        runner.run(ctx -> assertThat(ctx).getFailure()
                .rootCause().isInstanceOf(NoSuchBeanDefinitionException.class)
                .hasMessageContaining(OutboxDefinition.class.getName()));
    }

    @Test
    void 전송을_고르지_않으면_기동이_실패한다() {
        runner.withPropertyValues("nova.outbox.transport=")
                .withBean(OutboxDefinition.class, () -> definition(TestOutbox.TABLE))
                .run(ctx -> assertThat(ctx).getFailure()
                        .rootCause().isInstanceOf(NoSuchBeanDefinitionException.class)
                        .hasMessageContaining(MessageTransport.class.getName()));
    }

    /** 보내는 동안 리스가 끝나면 다른 인스턴스가 같은 행을 또 보낸다. 리스가 전송 한 번 + 여유(5s)보다 길지 않으면 뜨지 않는다. */
    @Test
    void 릴레이_리스가_전송_한_번의_최대_시간보다_길지_않으면_기동이_실패한다() {
        MessageTransport slow = new MessageTransport() {
            @Override
            public void send(OutboundMessage message) {
            }

            @Override
            public Duration maxSendTime() {
                return Duration.ofMinutes(1);
            }
        };
        runner.withPropertyValues("nova.outbox.transport=", "nova.outbox.relay-lease=65s")
                .withBean(MessageTransport.class, () -> slow)
                .withBean(OutboxDefinition.class, () -> definition(TestOutbox.TABLE))
                .run(ctx -> assertThat(ctx).getFailure().rootCause().hasMessageContaining("nova.outbox.relay-lease"));
        runner.withPropertyValues("nova.outbox.transport=", "nova.outbox.relay-lease=66s")
                .withBean(MessageTransport.class, () -> slow)
                .withBean(OutboxDefinition.class, () -> definition(TestOutbox.TABLE))
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    /** ddl-auto: validate 가 이 표를 보지 않는다. 칸이 빠졌거나 표가 없으면 릴레이가 돌 때가 아니라 기동할 때 드러나야 한다. */
    @Test
    void 표가_없거나_칸이_빠졌으면_기동이_실패한다() {
        runner.withBean(OutboxDefinition.class, () -> definition("it_outbox_missing")).run(ctx ->
                assertThat(ctx).getFailure().hasMessageContaining("it_outbox_missing"));
        runner.withBean(OutboxDefinition.class, () -> definition("it_outbox_without_lease")).run(ctx ->
                assertThat(ctx).getFailure().hasMessageContaining("it_outbox_without_lease"));
    }

    /**
     * Executor · TaskScheduler · ScheduledExecutorService 빈을 하나라도 두면 Boot 가 기본 applicationTaskExecutor ·
     * taskScheduler 를 만들지 않아 서비스의 @Async · @Scheduled 가 이 모듈의 실행기로 몰린다.
     */
    @Test
    void 서비스의_실행기_스케줄러_빈을_만들지_않는다() {
        runner.withBean(OutboxDefinition.class, () -> definition(TestOutbox.TABLE)).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeansOfType(Executor.class)).isEmpty();
            assertThat(ctx.getBeansOfType(TaskScheduler.class)).isEmpty();
            assertThat(ctx.getBeansOfType(ScheduledExecutorService.class)).isEmpty();
        });
    }

    @Test
    void micrometer_가_없어도_뜨고_지표를_남기지_않는다() {
        runner.withClassLoader(new FilteredClassLoader(MeterRegistry.class))
                .withBean(OutboxDefinition.class, () -> definition(TestOutbox.TABLE))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(OutboxMetrics.class)).isSameAs(OutboxMetrics.NONE);
                });
    }

    @Test
    void 레지스트리_빈이_없으면_지표를_남기지_않는다() {
        runner.withBean(OutboxDefinition.class, () -> definition(TestOutbox.TABLE)).run(ctx ->
                assertThat(ctx.getBean(OutboxMetrics.class)).isSameAs(OutboxMetrics.NONE));
    }

    @Test
    void 레지스트리가_있으면_서비스가_정한_접두어로_센다() {
        runner.withPropertyValues("nova.outbox.metrics-prefix=preorder.outbox")
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(OutboxDefinition.class, () -> definition(TestOutbox.TABLE))
                .run(ctx -> {
                    MeterRegistry registry = ctx.getBean(MeterRegistry.class);
                    OutboxMetrics metrics = ctx.getBean(OutboxMetrics.class);

                    metrics.published("ITEM_SETTLED", false);
                    metrics.refreshBacklog();

                    assertThat(registry.get("preorder.outbox.publish")
                            .tags("eventType", "ITEM_SETTLED", "outcome", "failure").counter().count()).isEqualTo(1);
                    // 공유 시험 DB 라 값은 그 순간의 표와 맞춰 본다
                    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
                    assertThat(registry.get("preorder.outbox.unpublished").gauge().value()).isEqualTo(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM it_outbox_events WHERE published_at IS NULL", Double.class));
                    assertThat(registry.get("preorder.outbox.unpublished.max.attempts").gauge().value()).isEqualTo(
                            jdbc.queryForObject("SELECT COALESCE(MAX(publish_attempts), 0) FROM it_outbox_events"
                                    + " WHERE published_at IS NULL", Double.class));
                });
    }

    private static OutboxDefinition definition(String table) {
        return new OutboxDefinition(table, List.of(TestOutbox.EventType.values()));
    }
}
