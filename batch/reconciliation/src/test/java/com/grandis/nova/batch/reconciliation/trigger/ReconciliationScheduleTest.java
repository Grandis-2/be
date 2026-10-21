package com.grandis.nova.batch.reconciliation.trigger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.grandis.nova.batch.reconciliation.ReconciliationService;
import com.grandis.nova.batch.reconciliation.config.ReconciliationProperties;
import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/** 작업은 configureTasks 가 끝난 뒤에 스케줄된다. 정리가 어떤 등록보다도 앞서야 막 가져간 요청을 정리하는 일이 없다 */
class ReconciliationScheduleTest {

    private final ReconciliationProperties properties = new ReconciliationProperties(true, "0 0 4 * * *",
            ZoneId.of("Asia/Seoul"), Duration.ofSeconds(5), Duration.ofMinutes(5), Duration.ofDays(1),
            Duration.ofMinutes(2), Duration.ofMinutes(45), Duration.ofHours(1), 1000, 10000, Duration.ofMinutes(30));

    private final ReconciliationService service = mock(ReconciliationService.class);
    private final ScheduledTaskRegistrar registrar = mock(ScheduledTaskRegistrar.class);

    @Test
    void 정리한_뒤_요청_확인_정기_실행_마감_뒤_실행을_등록한다() {
        new ReconciliationSchedule(service, properties).configureTasks(registrar);

        verify(service).cleanupAfterRestart();
        verify(registrar, times(2)).addFixedDelayTask(any(Runnable.class), any(Duration.class));
        verify(registrar).addCronTask(any(CronTask.class));
    }

    @Test
    void 정리가_어떤_작업_등록보다도_먼저다() {
        doThrow(new IllegalStateException("정리 중 멈춤")).when(service).cleanupAfterRestart();

        assertThatThrownBy(() -> new ReconciliationSchedule(service, properties).configureTasks(registrar))
                .hasMessage("정리 중 멈춤");
        verifyNoInteractions(registrar);
    }
}
