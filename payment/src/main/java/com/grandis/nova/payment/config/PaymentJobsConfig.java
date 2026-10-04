package com.grandis.nova.payment.config;

import com.grandis.nova.payment.confirm.PendingCaptureExpiry;
import com.grandis.nova.payment.recovery.RecoveryWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 주기 작업을 켠다. payment 의 첫 @Scheduled 라 스케줄링도 여기서 켠다(공통 모듈은 켜지 않는다 — 서비스가 정한다).
 * nova.payment.jobs.enabled=false 면 스케줄링 · 작업 빈이 모두 없다 — 통합 테스트가 커밋된 행을 공유하므로 작업을 직접 부른다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty(name = "nova.payment.jobs.enabled", matchIfMissing = true)
class PaymentJobsConfig {

    /** 주기 작업 수(승인 복구 · 환불 · 결제창 만료). 실행기 스레드가 이보다 적으면 서로 밀린다. */
    static final int JOB_COUNT = 3;

    @Bean
    PaymentJobs paymentJobs(RecoveryWorker recovery, PendingCaptureExpiry expiry) {
        return new PaymentJobs(recovery, expiry);
    }

    /**
     * 실행기 스레드를 작업 수 이상으로 둔다. spring.task.scheduling.pool.size 는 커밋하지 않는 application.yml 에만 있어, 빠뜨리면
     * Boot 기본값 1 이 되어 환불 적체가 승인 복구를 다시 밀어낸다. 설정 값이 더 크면 그 값을 쓴다.
     */
    @Bean
    ThreadPoolTaskSchedulerCustomizer paymentJobsSchedulerThreads() {
        return scheduler -> scheduler.setPoolSize(Math.max(scheduler.getPoolSize(), JOB_COUNT));
    }
}
