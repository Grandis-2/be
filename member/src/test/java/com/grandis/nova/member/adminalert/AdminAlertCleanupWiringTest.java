package com.grandis.nova.member.adminalert;

import com.grandis.nova.member.auth.infrastructure.db.RefreshTokenCleanupConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 주기 실행의 배선 — member 의 @EnableScheduling(RefreshTokenCleanupConfiguration)이 이 작업에도 걸리는지, @Scheduled 의 설정 키가 맞는지
 * (끄기 스위치 "-" 가 조용히 무시되지 않는지). DB 없이 설정 클래스 · 작업 빈만 올린다(리프레시 정리의 배선 시험과 같은 방식).
 */
@DisplayName("관리자 알림 정리 — 스케줄 배선")
class AdminAlertCleanupWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RefreshTokenCleanupConfiguration.class)
            .withBean(AdminAlertCleanup.class, () -> new AdminAlertCleanup(mock(JdbcTemplate.class), mock(TargetNames.class), Clock.systemUTC(),
                    new AdminAlertCleanup.Settings(Duration.ofDays(30), 500, 20, Duration.ofDays(1), 20)));

    @Test
    @DisplayName("기본은 10분마다 한 번")
    void registeredWithDefaultCron() {
        runner.run(ctx -> assertThat(cleanupTasks(ctx.getBean(ScheduledTaskHolder.class)))
                .singleElement().extracting(CronTask::getExpression).isEqualTo("0 */10 * * * *"));
    }

    @Test
    @DisplayName("설정한 cron 이 쓰인다")
    void cronFromProperty() {
        runner.withPropertyValues("member.admin-alert.cleanup.cron=0 0 * * * *")
                .run(ctx -> assertThat(cleanupTasks(ctx.getBean(ScheduledTaskHolder.class)))
                        .singleElement().extracting(CronTask::getExpression).isEqualTo("0 0 * * * *"));
    }

    @Test
    @DisplayName("\"-\" 면 등록되지 않는다(끄기 스위치)")
    void disabledWithDash() {
        runner.withPropertyValues("member.admin-alert.cleanup.cron=-")
                .run(ctx -> assertThat(cleanupTasks(ctx.getBean(ScheduledTaskHolder.class))).isEmpty());
    }

    private static List<CronTask> cleanupTasks(ScheduledTaskHolder holder) {
        return holder.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(task -> task.toString().contains(AdminAlertCleanup.class.getName()))
                .map(CronTask.class::cast)
                .toList();
    }
}
