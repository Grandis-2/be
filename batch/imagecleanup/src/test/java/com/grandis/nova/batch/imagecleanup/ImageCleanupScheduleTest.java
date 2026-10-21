package com.grandis.nova.batch.imagecleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.batch.imagecleanup.support.ImageCleanupIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 진입점의 @EnableScheduling 이 빠지면 정리가 조용히 돌지 않는다 — 앱을 띄워 cron 작업이 실제로 등록되는지 본다.
 * 속성이 없을 때 켜지는 경로는 ImageCleanupScheduleConditionTest 가 본다. 이 컨텍스트는 시험이 끝나면 닫아 cron 이 남아 돌지 않게 한다.
 */
@TestPropertySource(properties = "nova.batch.image-cleanup.scheduling-enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ImageCleanupScheduleTest extends ImageCleanupIntegrationTest {

    @Autowired
    ScheduledTaskHolder scheduledTasks;

    @Test
    void 기동하면_정기_실행이_등록된다() {
        assertThat(scheduledTasks.getScheduledTasks())
                .map(ScheduledTask::getTask)
                .filteredOn(CronTask.class::isInstance)
                .map(task -> ((CronTask) task).getExpression())
                .containsExactly("0 0 5 * * *");
    }
}
