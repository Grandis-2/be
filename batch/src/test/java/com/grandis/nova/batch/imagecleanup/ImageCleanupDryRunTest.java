package com.grandis.nova.batch.imagecleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.batch.support.BatchIntegrationTest;
import com.grandis.nova.batch.support.TestData.ImageRow;
import com.grandis.nova.common.jpa.StorageClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * 기본 설정(dry-run)으로 돌린다 — 업로드 버킷 · 리전 설정 없이 뜨고, 대상을 세어 로그만 남긴다.
 */
@ExtendWith(OutputCaptureExtension.class)
class ImageCleanupDryRunTest extends BatchIntegrationTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final Instant OLD = NOW.minus(Duration.ofHours(25));

    @TestBean(name = "storageClock", methodName = "fixedClock")
    Clock storageClock;

    @Autowired
    ImageCleanupService service;

    @Autowired
    ImageObjects objects;

    static Clock fixedClock() {
        return StorageClock.atStorageResolution(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void dry_run_은_대상을_세어_로그만_남기고_지우지_않는다(CapturedOutput output) {
        ImageRow oldest = data.image("products", "UNUSED", OLD.minus(Duration.ofHours(1)));
        data.image("promotions", "UNUSED", OLD);
        ImageRow drawn = data.image("products", "UNUSED", OLD.minus(Duration.ofHours(2)));
        data.draw("폰", "https://cdn.example.com/" + drawn.key());
        ImageRow ordered = data.image("products", "UNUSED", OLD.minus(Duration.ofHours(3)));
        data.orderItem("노트북", "https://cdn.example.com/" + ordered.key());
        data.image("products", "UNUSED", NOW.minus(Duration.ofHours(1)));
        data.pendingDeletion("products/left-over.jpg", NOW.minus(Duration.ofDays(1)));

        service.run();

        assertThat(objects).isSameAs(ImageObjects.DRY_RUN);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM images").query(Long.class).single()).isEqualTo(5);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM image_deletions").query(Long.class).single()).isEqualTo(1);
        assertThat(output).contains("사진 정리(dry-run, 지우지 않음)")
                .contains("UNUSED 가 된 사진 4건: 지울 대상 2 · 드로우 · 지난 주문이 가리켜 남김 2 · 지난 실행의 삭제 대기 1건")
                .contains("오래된 순 [" + oldest.key());
    }
}
