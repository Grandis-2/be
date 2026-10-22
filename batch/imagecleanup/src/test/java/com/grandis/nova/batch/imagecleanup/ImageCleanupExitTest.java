package com.grandis.nova.batch.imagecleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.batch.imagecleanup.support.FakeS3;
import com.grandis.nova.batch.imagecleanup.support.ImageCleanupIntegrationTest;
import com.grandis.nova.batch.imagecleanup.support.MySqlTestContainer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 진입점이 돌려주는 값이 프로세스 종료 코드이고, 배치 실패 경보가 이 값을 본다. 실제 진입 경로(execute)로 앱을 띄워 정리 결과와
 * 기동 실패가 0 · 2 · 1 로 끝나는지 본다 — 실행기의 종료 코드를 진입점까지 잇지 않으면 막힌 정리가 0 으로 끝나 경보가 사라진다.
 * 앱은 경우마다 새로 띄우고 닫는다. 행은 통합 시험 기반의 컨텍스트(뜰 때 스키마를 만든다)로 넣는다.
 */
class ImageCleanupExitTest extends ImageCleanupIntegrationTest {

    private static final String BUCKET = "test-uploads";
    private static final FakeS3 S3 = FakeS3.start(BUCKET);

    private final MySQLContainer mysql = MySqlTestContainer.get();

    @BeforeEach
    void resetS3() {
        S3.reset();
    }

    @Test
    void 정리를_마치면_0_으로_끝난다() {
        assertThat(ImageCleanupApplication.execute(args())).isZero();
    }

    @Test
    void 삭제_대기가_3일_넘게_남아_막혀_있으면_2_로_끝난다() {
        String stuck = "products/" + UUID.randomUUID() + ".jpg";
        data.pendingDeletion(stuck, Instant.now().minus(Duration.ofDays(4)));
        S3.failDelete(stuck, 500, S3ImageObjects.MAX_ATTEMPTS);

        int exitCode = ImageCleanupApplication.execute(args("--nova.batch.image-cleanup.dry-run=false",
                "--nova.upload.bucket=" + BUCKET, "--nova.upload.region=ap-northeast-2", "--nova.upload.endpoint=" + S3.url()));

        assertThat(exitCode).isEqualTo(ImageCleanupRunner.BLOCKED_EXIT_CODE);
    }

    @Test
    void 기동하지_못하면_1_로_끝난다() {
        // 기준 시간이 하한(1시간)보다 짧다
        assertThat(ImageCleanupApplication.execute(args("--nova.batch.image-cleanup.retention=1m"))).isEqualTo(1);
    }

    /** 시험 프로필(마이그레이션 설정)과 시험 DB 에 붙이고, 시험 프로필이 꺼 둔 기동 실행을 켠다 */
    private String[] args(String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.profiles.active=test",
                "--spring.datasource.url=" + mysql.getJdbcUrl(),
                "--spring.datasource.username=" + mysql.getUsername(),
                "--spring.datasource.password=" + mysql.getPassword(),
                "--nova.batch.image-cleanup.run-on-startup=true"));
        args.addAll(List.of(extra));
        return args.toArray(String[]::new);
    }
}
