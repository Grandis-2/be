package com.grandis.nova.preorder.deadletter.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

@Configuration(proxyBeanMethods = false)
class DeadLetterConfig {

    static final String REDRIVE_EXECUTOR = "deadLetterRedriveExecutor";

    /** 일괄 되돌리기를 천천히 보내는 가상 스레드. 종료 때 남은 건은 버린다 — 되돌리기 대기로 남아 다시 요청하면 이어진다. */
    @Bean(name = REDRIVE_EXECUTOR)
    SimpleAsyncTaskExecutor deadLetterRedriveExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("dead-letter-redrive-");
        executor.setVirtualThreads(true);
        executor.setCancelRemainingTasksOnClose(true);
        return executor;
    }
}
