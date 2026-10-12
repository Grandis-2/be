package com.grandis.nova.batch.reconciliation.trigger;

import com.grandis.nova.batch.reconciliation.ReconciliationService;
import com.grandis.nova.batch.reconciliation.config.ReconciliationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

/**
 * 주기 작업과 기동 정리. 시험은 nova.batch.reconciliation.scheduling-enabled=false 로 끄고 서비스를 직접 부른다.
 * 작업은 스케줄러 스레드 하나에서 차례로 돈다 — 검사가 도는 동안 요청 확인은 기다린다(대기 · 실행 중은 어차피 하나뿐이다).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "nova.batch.reconciliation", name = "scheduling-enabled", havingValue = "true",
        matchIfMissing = true)
class ReconciliationSchedule implements SchedulingConfigurer {

    private final ReconciliationService service;
    private final ReconciliationProperties properties;

    ReconciliationSchedule(ReconciliationService service, ReconciliationProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(service::runRequested, properties.requestPollInterval());
        registrar.addCronTask(new CronTask(service::runScheduled, new CronTrigger(properties.cron(), properties.zone())));
        registrar.addFixedDelayTask(service::runAfterCampaignClose, properties.campaignCloseCheckInterval());
    }

    /** 주기 작업이 돌기 전에 끝나지 않은 실행부터 정리한다 */
    @EventListener(ApplicationReadyEvent.class)
    void cleanupAfterRestart() {
        service.cleanupAfterRestart();
    }
}
