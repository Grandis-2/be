package com.grandis.nova.preorder.integration.catalog;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.web.service.registry.ImportHttpServices;

/** catalog 내부 API 클라이언트. 주소 · 타임아웃은 spring.http.serviceclient.catalog 설정으로 준다. */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "catalog", types = CatalogClient.class)
class CatalogClientConfig {

    /** 다시 받기는 상품마다 한 번만 돌므로 상한은 넉넉하다. 넘치면 기다리지 않고 거절한다 — 다음 조회가 다시 시도한다. */
    static final int REFRESH_CONCURRENCY = 16;

    /** 캐시 다시 받기용 가상 스레드. 종료 때 남은 건은 버린다 — 가진 값을 쓰다가 다음 조회가 다시 받는다. */
    @Bean(name = CatalogReader.REFRESH_EXECUTOR)
    SimpleAsyncTaskExecutor catalogRefreshExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("catalog-refresh-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(REFRESH_CONCURRENCY);
        executor.setRejectTasksWhenLimitReached(true);
        executor.setCancelRemainingTasksOnClose(true);
        return executor;
    }
}
