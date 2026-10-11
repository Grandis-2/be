package com.grandis.nova.common.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;

/**
 * 트랜잭셔널 아웃박스. 의존하면 켜진다 — 서비스는 OutboxDefinition 빈(표 이름 · 이벤트 종류)과 nova.outbox.transport 를 준다.
 * 둘 중 하나라도 없으면 기동이 실패한다(보내지 않고 쌓이기만 하는 아웃박스를 막는다).
 *
 * 자동설정이라 서비스의 컴포넌트 스캔(com.grandis.nova)에서는 빠진다. 공통 클래스에는 @Component 를 붙이지 않는다 — 붙이면 두 번 등록된다.
 * 시계는 서비스의 Clock 빈(common:jpa 의 저장 시계)을 쓰고, 없으면 UTC 시스템 시계를 쓴다.
 */
@AutoConfiguration
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    private static final Duration STOP_MARGIN = Duration.ofSeconds(5);
    private static final Duration LEASE_MARGIN = Duration.ofSeconds(5);

    /** 기동할 때 표를 읽으므로 앱이 DB 초기화(스크립트 등)를 쓰면 그 뒤에 만든다. */
    @Bean
    @DependsOnDatabaseInitialization
    OutboxStore outboxStore(DataSource dataSource, OutboxDefinition definition) {
        return new OutboxStore(dataSource, definition);
    }

    @Bean
    OutboxWriter outboxWriter(OutboxStore store, OutboxDefinition definition, JsonMapper jsonMapper,
                              ApplicationEventPublisher eventPublisher, ObjectProvider<Clock> clock) {
        return new OutboxWriter(store, definition, jsonMapper, eventPublisher, clockOf(clock));
    }

    @Bean
    OutboxPublisher outboxPublisher(OutboxStore store, OutboxDefinition definition, MessageTransport transport,
                                    JsonMapper jsonMapper, ObjectProvider<Clock> clock, OutboxMetrics metrics,
                                    OutboxProperties properties) {
        // 리스는 연장한 뒤 전송과 발행 완료 표시(DB)까지 지켜야 한다 — 전송 시간만큼만 길면 DB 가 조금만 늦어도 남이 가져간다
        Duration needed = transport.maxSendTime().plus(LEASE_MARGIN);
        if (properties.relayLease().compareTo(needed) <= 0) {
            throw new IllegalStateException("nova.outbox.relay-lease(" + properties.relayLease() + ")는 전송 한 번의 최대 시간("
                    + transport.maxSendTime() + ")에 여유 " + LEASE_MARGIN + " 를 더한 것보다 길어야 한다"
                    + " — 보내는 동안 리스가 끝나 같은 행을 두 번 보낸다");
        }
        return new OutboxPublisher(store, definition, transport, jsonMapper, clockOf(clock), metrics);
    }

    @Bean
    OutboxAfterCommitPublisher outboxAfterCommitPublisher(OutboxPublisher publisher, OutboxProperties properties) {
        return new OutboxAfterCommitPublisher(publisher, OutboxAfterCommitPublisher.executor(properties.concurrency()));
    }

    @Bean
    OutboxRelay outboxRelay(OutboxStore store, OutboxPublisher publisher, OutboxProperties properties,
                            PlatformTransactionManager transactionManager, ObjectProvider<Clock> clock) {
        return new OutboxRelay(store, publisher, properties, new TransactionTemplate(transactionManager), clockOf(clock));
    }

    /** 종료 때는 보내던 한 건만 기다린다 — 전송 한 번의 최대 시간에 여유를 더한다. */
    @Bean
    OutboxRelayScheduler outboxRelayScheduler(OutboxRelay relay, OutboxMetrics metrics, OutboxProperties properties,
                                              MessageTransport transport) {
        return new OutboxRelayScheduler(relay, metrics, properties.relayInterval(),
                transport.maxSendTime().plus(STOP_MARGIN));
    }

    @Bean
    OutboxCleaner outboxCleaner(OutboxStore store, OutboxMetrics metrics, OutboxProperties properties,
                                ObjectProvider<Clock> clock) {
        return new OutboxCleaner(store, metrics, properties, clockOf(clock));
    }

    @Bean
    @ConditionalOnProperty(name = "nova.outbox.transport", havingValue = "log")
    LoggingMessageTransport loggingMessageTransport(Environment environment) {
        return new LoggingMessageTransport(
                environment.getProperty(LoggingMessageTransport.ALLOWED_PROPERTY, Boolean.class, false));
    }

    @Bean
    @ConditionalOnMissingClass("io.micrometer.core.instrument.MeterRegistry")
    OutboxMetrics noOutboxMetrics() {
        return OutboxMetrics.NONE;
    }

    private static Clock clockOf(ObjectProvider<Clock> clock) {
        return clock.getIfAvailable(Clock::systemUTC);
    }

    /** micrometer 가 클래스패스에 있을 때만 읽힌다. 레지스트리 빈이 없으면(actuator 없음) 기록하지 않는다. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class Metrics {

        @Bean
        OutboxMetrics outboxMetrics(ObjectProvider<MeterRegistry> registry, OutboxStore store,
                                    OutboxProperties properties) {
            MeterRegistry meterRegistry = registry.getIfAvailable();
            return meterRegistry == null ? OutboxMetrics.NONE
                    : new MicrometerOutboxMetrics(meterRegistry, store, properties.metricsPrefix());
        }
    }
}
