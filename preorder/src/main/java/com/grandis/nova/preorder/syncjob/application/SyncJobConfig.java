package com.grandis.nova.preorder.syncjob;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

@Configuration(proxyBeanMethods = false)
class SyncJobConfig {

    static final String REPROCESS_EXECUTOR = "syncJobReprocessExecutor";

    /** 일괄 재처리를 천천히 기록하는 가상 스레드. 종료 때 남은 건은 버린다 — 같은 요청을 다시 보내면 이어진다. */
    @Bean(name = REPROCESS_EXECUTOR)
    SimpleAsyncTaskExecutor syncJobReprocessExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("sync-job-reprocess-");
        executor.setVirtualThreads(true);
        executor.setCancelRemainingTasksOnClose(true);
        return executor;
    }
}
