package com.grandis.nova.batch.imagecleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 운영은 run-on-startup 을 적지 않는다 — 속성이 없을 때 켜지는 경로(matchIfMissing)를 DB 없이 본다 */
class ImageCleanupRunnerConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ImageCleanupRunner.class)
            .withBean(ImageCleanupService.class, () -> mock(ImageCleanupService.class));

    @Test
    void 속성을_적지_않으면_기동_실행이_켜진다() {
        runner.run(context -> assertThat(context).hasSingleBean(ImageCleanupRunner.class));
    }

    @Test
    void false_로_끄면_등록하지_않는다() {
        runner.withPropertyValues("nova.batch.image-cleanup.run-on-startup=false")
                .run(context -> assertThat(context).doesNotHaveBean(ImageCleanupRunner.class));
    }
}
