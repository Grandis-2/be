package com.grandis.nova.batch.imagecleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 운영은 scheduling-enabled 를 적지 않는다 — 속성이 없을 때 켜지는 경로(matchIfMissing)를 DB 없이 본다 */
class ImageCleanupScheduleConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ImageCleanupSchedule.class)
            .withBean(ImageCleanupService.class, () -> mock(ImageCleanupService.class))
            .withBean(ImageCleanupProperties.class, () -> new ImageCleanupProperties(
                    true, "0 0 5 * * *", ZoneId.of("Asia/Seoul"), Duration.ofHours(24), 500, true));

    @Test
    void 속성을_적지_않으면_정기_실행이_켜진다() {
        runner.run(context -> assertThat(context).hasSingleBean(ImageCleanupSchedule.class));
    }

    @Test
    void false_로_끄면_등록하지_않는다() {
        runner.withPropertyValues("nova.batch.image-cleanup.scheduling-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ImageCleanupSchedule.class));
    }
}
