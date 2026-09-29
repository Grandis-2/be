package com.grandis.nova.order.outbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * common:outbox 이전 시: preorder outbox.publish.OutboxConfig 의 복사본이다. 공통 쪽 자동 설정으로 옮긴다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
class OutboxConfig {

    /** 종료 시 진행 중인 발행을 기다리는 시간. */
    private static final long TERMINATION_TIMEOUT_MILLIS = 5_000;

    /**
     * 커밋 직후 발행 실행기. 건마다 가상 스레드를 쓰고 동시 실행 수로 상한을 둔다.
     * 넘치면 기다리지 않고 거절해 요청 스레드를 붙잡지 않는다(릴레이가 보낸다).
     */
    @Bean(name = OutboxAfterCommitPublisher.EXECUTOR)
    SimpleAsyncTaskExecutor outboxPublishExecutor(OutboxProperties properties) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("outbox-publish-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(properties.concurrency());
        executor.setRejectTasksWhenLimitReached(true);
        executor.setTaskTerminationTimeout(TERMINATION_TIMEOUT_MILLIS);
        return executor;
    }
}
