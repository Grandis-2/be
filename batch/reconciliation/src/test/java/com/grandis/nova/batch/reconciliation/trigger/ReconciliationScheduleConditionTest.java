package com.grandis.nova.batch.reconciliation.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.grandis.nova.batch.reconciliation.ReconciliationService;
import com.grandis.nova.batch.reconciliation.config.ReconciliationProperties;
import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 운영은 scheduling-enabled 를 적지 않는다 — 속성이 없을 때 켜지는 경로(matchIfMissing)를 DB 없이 본다 */
class ReconciliationScheduleConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ReconciliationSchedule.class)
            .withBean(ReconciliationService.class, () -> mock(ReconciliationService.class))
            .withBean(ReconciliationProperties.class, () -> new ReconciliationProperties(true, "0 0 4 * * *",
                    ZoneId.of("Asia/Seoul"), Duration.ofSeconds(5), Duration.ofMinutes(5), Duration.ofDays(1),
                    Duration.ofMinutes(2), Duration.ofMinutes(45), Duration.ofHours(1), 1000, 10000, Duration.ofMinutes(30)));

    @Test
    void 속성을_적지_않으면_주기_작업이_켜진다() {
        runner.run(context -> assertThat(context).hasSingleBean(ReconciliationSchedule.class));
    }

    @Test
    void false_로_끄면_등록하지_않는다() {
        runner.withPropertyValues("nova.batch.reconciliation.scheduling-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ReconciliationSchedule.class));
    }
}
