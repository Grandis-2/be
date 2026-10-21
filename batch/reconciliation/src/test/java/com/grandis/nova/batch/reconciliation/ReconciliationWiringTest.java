package com.grandis.nova.batch.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.grandis.nova.batch.reconciliation.support.ReconciliationIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockReset;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 진입점의 @EnableScheduling 이 빠지면 기동 정리 · 요청 확인 · 정기 실행 · 마감 뒤 실행이 조용히 돌지 않는다 — 앱을 띄워 등록을 본다.
 * 서비스는 막아 5초 요청 확인이 다른 시험의 행을 가져가지 못하게 하고, 시험이 끝나면 컨텍스트를 닫는다.
 * 속성이 없을 때 켜지는 경로는 trigger.ReconciliationScheduleConditionTest 가 본다.
 */
@TestPropertySource(properties = "nova.batch.reconciliation.scheduling-enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReconciliationWiringTest extends ReconciliationIntegrationTest {

    // 기동 때 한 번 불린 기록을 메서드가 끝나도 지우지 않는다(기본값은 메서드마다 비운다). 컨텍스트는 클래스가 끝나면 닫힌다
    @MockitoBean(reset = MockReset.NONE)
    ReconciliationService service;

    @Autowired
    ScheduledTaskHolder scheduledTasks;

    @Test
    void 기동하면_기동_정리를_하고_요청_확인_정기_실행_마감_뒤_실행이_등록된다() {
        verify(service).cleanupAfterRestart();
        assertThat(scheduledTasks.getScheduledTasks()).map(ScheduledTask::getTask)
                .filteredOn(FixedDelayTask.class::isInstance).hasSize(2);
        assertThat(scheduledTasks.getScheduledTasks()).map(ScheduledTask::getTask)
                .filteredOn(CronTask.class::isInstance).hasSize(1);
    }
}
