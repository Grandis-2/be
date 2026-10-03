package com.grandis.nova.payment.config;

import com.grandis.nova.payment.confirm.PendingCaptureExpiry;
import com.grandis.nova.payment.recovery.RecoveryWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
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

    @Bean
    PaymentJobs paymentJobs(RecoveryWorker recovery, PendingCaptureExpiry expiry) {
        return new PaymentJobs(recovery, expiry);
    }
}
