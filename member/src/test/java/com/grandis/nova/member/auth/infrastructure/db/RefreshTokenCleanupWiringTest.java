package com.grandis.nova.member.auth.infrastructure.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.grandis.nova.common.security.JwtProperties;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * 주기 실행의 배선 — @EnableScheduling 이 빠지거나 @Scheduled 의 설정 키가 틀리면(끄기 스위치 "-" 가 조용히 무시된다) 여기서 걸린다.
 * DB 없이 설정 클래스 · 작업 빈만 올린다.
 */
@DisplayName("만료 리프레시 정리 — 스케줄 배선")
class RefreshTokenCleanupWiringTest {

    // 작업 빈은 직접 만들어 넣는다(@Scheduled 처리는 빈이 어떻게 생겼든 붙는다). JwtProperties 를 빈으로 두면 설정 바인딩이 가짜 빈에 걸린다
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RefreshTokenCleanupConfiguration.class)
            .withBean(RefreshTokenCleanup.class, () -> new RefreshTokenCleanup(mock(RefreshTokenRepository.class), Clock.systemUTC(),
                    new RefreshTokenCleanup.Settings(1000, 100), mock(JwtProperties.class)));

    @Test
    @DisplayName("기본은 10분마다 한 번 — 정리 작업 하나가 cron 으로 등록된다")
    void registeredWithDefaultCron() {
        runner.run(ctx -> assertThat(cleanupTasks(ctx.getBean(ScheduledTaskHolder.class)))
                .singleElement().extracting(CronTask::getExpression).isEqualTo("0 */10 * * * *"));
    }

    @Test
    @DisplayName("설정한 cron 이 쓰인다")
    void cronFromProperty() {
        runner.withPropertyValues("member.refresh-cleanup.cron=0 0 * * * *")
                .run(ctx -> assertThat(cleanupTasks(ctx.getBean(ScheduledTaskHolder.class)))
                        .singleElement().extracting(CronTask::getExpression).isEqualTo("0 0 * * * *"));
    }

    @Test
    @DisplayName("\"-\" 면 등록되지 않는다(끄기 스위치)")
    void disabledWithDash() {
        runner.withPropertyValues("member.refresh-cleanup.cron=-")
                .run(ctx -> assertThat(cleanupTasks(ctx.getBean(ScheduledTaskHolder.class))).isEmpty());
    }

    @Test
    @DisplayName("묶음 크기 · 묶음 수가 0 이하면 기동 실패")
    void nonPositiveSettingsFail() {
        assertThatThrownBy(() -> new RefreshTokenCleanup.Settings(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RefreshTokenCleanup.Settings(1, 0)).isInstanceOf(IllegalArgumentException.class);
        runner.withPropertyValues("member.refresh-cleanup.batch-size=0").run(ctx -> assertThat(ctx).hasFailed());
    }

    private static List<CronTask> cleanupTasks(ScheduledTaskHolder holder) {
        return holder.getScheduledTasks().stream()
                .map(ScheduledTask::getTask)
                .filter(task -> task.toString().contains(RefreshTokenCleanup.class.getName()))
                .map(CronTask.class::cast)
                .toList();
    }
}
