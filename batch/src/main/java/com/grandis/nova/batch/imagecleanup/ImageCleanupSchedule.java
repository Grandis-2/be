package com.grandis.nova.batch.imagecleanup;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

/**
 * 사진 정리 정기 실행. 시험은 nova.batch.image-cleanup.scheduling-enabled=false 로 끄고 서비스를 직접 부른다.
 * 정합성 검사와 같은 스케줄러 스레드 하나에서 차례로 돈다 — 05:00 에 검사가 아직 돌면 그 검사가 끝난 뒤 시작한다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "nova.batch.image-cleanup", name = "scheduling-enabled", havingValue = "true",
        matchIfMissing = true)
class ImageCleanupSchedule implements SchedulingConfigurer {

    private final ImageCleanupService service;
    private final ImageCleanupProperties properties;

    ImageCleanupSchedule(ImageCleanupService service, ImageCleanupProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addCronTask(new CronTask(service::run, new CronTrigger(properties.cron(), properties.zone())));
    }
}
