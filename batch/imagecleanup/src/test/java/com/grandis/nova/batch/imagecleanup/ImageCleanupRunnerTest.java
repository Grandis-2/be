package com.grandis.nova.batch.imagecleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.grandis.nova.batch.imagecleanup.ImageCleanupService.Result;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.QueryTimeoutException;

/**
 * 배치 실패 경보는 종료 코드가 0 · 143 이 아니면 울린다 — 정리 결과 중에는 며칠째 막힌 것만 0 이 아닌 값으로 끝내고, 예외는 그대로
 * 내보내 진입점이 1 로 끝낸다.
 */
class ImageCleanupRunnerTest {

    private final ImageCleanupService service = mock(ImageCleanupService.class);
    private final ImageCleanupRunner runner = new ImageCleanupRunner(service);

    @Test
    void 정리를_마치면_0_으로_끝낸다() {
        given(service.run()).willReturn(Result.DONE);

        runner.run(new DefaultApplicationArguments());

        verify(service).run();
        assertThat(runner.getExitCode()).isZero();
    }

    /** 다른 실행이 정리하는 중이다 — 실패가 아니다 */
    @Test
    void 잠금_때문에_건너뛰면_0_으로_끝낸다() {
        given(service.run()).willReturn(Result.SKIPPED);

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isZero();
    }

    @Test
    void 정리가_막혀_있으면_예외와_SIGTERM_이_아닌_값으로_끝낸다() {
        given(service.run()).willReturn(Result.BLOCKED);

        runner.run(new DefaultApplicationArguments());

        assertThat(runner.getExitCode()).isEqualTo(ImageCleanupRunner.BLOCKED_EXIT_CODE).isNotIn(0, 1, 143);
    }

    /** 예외는 진입점까지 나가 1 로 끝난다 — 여기서 잡아 0 으로 끝내면 경보가 울리지 않는다 */
    @Test
    void 정리_중_예외는_그대로_나간다() {
        QueryTimeoutException failure = new QueryTimeoutException("조회 시간 초과");
        given(service.run()).willThrow(failure);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments())).isSameAs(failure);
    }
}
