package com.grandis.nova.batch.imagecleanup;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

/**
 * 사진 정리 정기 실행. 시험은 nova.batch.image-cleanup.scheduling-enabled=false 로 끄고 서비스를 직접 부른다.
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
