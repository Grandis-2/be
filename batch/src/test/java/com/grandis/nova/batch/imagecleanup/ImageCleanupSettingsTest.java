package com.grandis.nova.batch.imagecleanup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 설정이 어긋나면 batch 가 뜨지 않는다 — 실행마다 조용히 실패하거나 짧은 기준 시간으로 지우는 대신 배포에서 드러난다.
 * 설정 바인딩부터 S3 클라이언트 빈까지 실제 경로로 띄운다(DB 는 쓰지 않는다).
 */
class ImageCleanupSettingsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ImageCleanupConfig.class);

    @Test
    void 기준_시간이_1시간보다_짧으면_뜨지_않는다() {
        // 24h 를 24m 으로 잘못 친 경우
        runner.withPropertyValues("nova.batch.image-cleanup.retention=24m")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessage("nova.batch.image-cleanup.retention 은 1시간 이상이어야 한다: PT24M"));
        runner.withPropertyValues("nova.batch.image-cleanup.retention=3599s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void 기준_시간이_1시간이면_뜬다() {
        runner.withPropertyValues("nova.batch.image-cleanup.retention=1h")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void 기준_시간을_적지_않으면_24시간이고_dry_run_이다() {
        runner.run(context -> {
            ImageCleanupProperties properties = context.getBean(ImageCleanupProperties.class);
            assertThat(properties.retention()).hasHours(24);
            assertThat(properties.dryRun()).isTrue();
            assertThat(context.getBean(ImageObjects.class)).isSameAs(ImageObjects.DRY_RUN);
        });
    }

    @Test
    void 청크_크기가_1보다_작거나_cron_식이_아니면_뜨지_않는다() {
        runner.withPropertyValues("nova.batch.image-cleanup.chunk-size=0")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessage("nova.batch.image-cleanup.chunk-size 는 1 이상이어야 한다"));
        runner.withPropertyValues("nova.batch.image-cleanup.cron=매일 5시")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void dry_run_을_끄면서_업로드_버킷이_없으면_뜨지_않는다() {
        runner.withPropertyValues("nova.batch.image-cleanup.dry-run=false")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessage("nova.upload.bucket 이 없다 — nova.batch.image-cleanup.dry-run 을 끄려면 사진을 지울 업로드 버킷이 있어야 한다"));
    }

    @Test
    void dry_run_을_끄고_업로드_버킷이_있으면_S3_로_지운다() {
        runner.withPropertyValues("nova.batch.image-cleanup.dry-run=false", "nova.upload.bucket=uploads",
                        "nova.upload.region=ap-northeast-2")
                .run(context -> assertThat(context.getBean(ImageObjects.class)).isInstanceOf(S3ImageObjects.class));
    }
}
