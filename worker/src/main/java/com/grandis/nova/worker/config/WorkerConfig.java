package com.grandis.nova.worker.config;

import com.grandis.nova.common.sqs.BatchQueueConsumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerProperties.class)
class WorkerConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * readiness 의 "consumers". 받기 · 삭제 스레드가 죽은 소비기가 있으면 DOWN — 아무것도 받지 않는 태스크가 떠 있지 않게 바꾸게 한다.
     * 소비를 끈 구성에서는 볼 소비기가 없어 늘 UP 이다.
     */
    @Bean
    HealthIndicator consumersHealthIndicator(ObjectProvider<BatchQueueConsumer> consumers) {
        return () -> consumers.orderedStream().allMatch(BatchQueueConsumer::isRunning)
                ? Health.up().build() : Health.down().build();
    }
}
