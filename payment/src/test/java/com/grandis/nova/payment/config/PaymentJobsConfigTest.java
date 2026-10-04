package com.grandis.nova.payment.config;

import com.grandis.nova.payment.confirm.PendingCaptureExpiry;
import com.grandis.nova.payment.recovery.RecoveryWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 주기 작업은 기본으로 켜지고(운영에서 빠뜨리면 불명 거래 · 환불 · 시작 안 된 결제창이 영영 남는다), 끄면 스케줄링까지 없다. */
class PaymentJobsConfigTest {

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PaymentJobsConfig.class)
            .withBean(RecoveryWorker.class, () -> mock(RecoveryWorker.class))
            .withBean(PendingCaptureExpiry.class, () -> mock(PendingCaptureExpiry.class));

    @Test
    void recoveryAndExpiryAreScheduledByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(PaymentJobs.class);
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks())
                    .extracting(ScheduledTask::getTask)
                    .allSatisfy(task -> assertThat(task).isInstanceOf(FixedDelayTask.class))
                    .extracting(task -> ((FixedDelayTask) task).getIntervalDuration())
                    .containsExactlyInAnyOrder(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(60));
        });
    }

    @Test
    void intervalsComeFromProperties() {
        runner.withPropertyValues("nova.payment.recovery.interval=2s", "nova.payment.refund.interval=7s",
                        "nova.payment.expiry.interval=30s")
                .run(context -> assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class)
                        .getScheduledTasks())
                        .extracting(task -> ((FixedDelayTask) task.getTask()).getIntervalDuration())
                        .containsExactlyInAnyOrder(Duration.ofSeconds(2), Duration.ofSeconds(7), Duration.ofSeconds(30)));
    }

    // 실행기 크기를 설정에서 빠뜨려도(Boot 기본값 1) 작업 셋이 서로 밀리지 않게 3 이상이다
    @Test
    void schedulerHasAThreadPerJobEvenWithoutPoolSize() {
        runner.withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .run(context -> assertThat(corePoolSize(context)).isEqualTo(PaymentJobsConfig.JOB_COUNT));
        runner.withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withPropertyValues("spring.task.scheduling.pool.size=5")
                .run(context -> assertThat(corePoolSize(context)).isEqualTo(5));
    }

    /** 시작된 실행기는 getPoolSize() 가 지금까지 만든 스레드 수다 — 설정된 크기는 코어 크기로 본다. */
    private static int corePoolSize(ApplicationContext context) {
        return context.getBean(ThreadPoolTaskScheduler.class).getScheduledThreadPoolExecutor().getCorePoolSize();
    }

    @Test
    void disabledLeavesNoJobsAndNoScheduling() {
        runner.withPropertyValues("nova.payment.jobs.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(PaymentJobs.class);
            assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
        });
    }
}
