package com.grandis.nova.worker.config;

import com.grandis.nova.common.sqs.BatchQueueConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 받기 · 삭제 스레드가 죽은 소비기가 하나라도 있으면 readiness 의 "consumers" 가 DOWN 이다. */
class ConsumersHealthTest {

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WorkerConfig.class)
            .withPropertyValues("nova.worker.mock.base-url=http://mock.nova.local:8081");

    @Test
    void 소비기가_모두_돌면_UP() {
        runner.withBean("register", BatchQueueConsumer.class, () -> consumer(true))
                .withBean("cancel", BatchQueueConsumer.class, () -> consumer(true))
                .run(ctx -> assertThat(status(ctx.getBean("consumersHealthIndicator", HealthIndicator.class)))
                        .isEqualTo(Status.UP));
    }

    @Test
    void 하나라도_멈췄으면_DOWN() {
        runner.withBean("register", BatchQueueConsumer.class, () -> consumer(true))
                .withBean("cancel", BatchQueueConsumer.class, () -> consumer(false))
                .run(ctx -> assertThat(status(ctx.getBean("consumersHealthIndicator", HealthIndicator.class)))
                        .isEqualTo(Status.DOWN));
    }

    @Test
    void 소비를_끈_구성은_UP() {
        runner.run(ctx -> assertThat(status(ctx.getBean("consumersHealthIndicator", HealthIndicator.class)))
                .isEqualTo(Status.UP));
    }

    private static BatchQueueConsumer consumer(boolean running) {
        BatchQueueConsumer consumer = mock(BatchQueueConsumer.class);
        when(consumer.isRunning()).thenReturn(running);
        return consumer;
    }

    private static Status status(HealthIndicator indicator) {
        return indicator.health().getStatus();
    }
}
