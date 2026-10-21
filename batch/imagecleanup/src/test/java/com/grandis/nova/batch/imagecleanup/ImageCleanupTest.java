package com.grandis.nova.batch.imagecleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.batch.imagecleanup.support.ImageCleanupIntegrationTest;
import com.grandis.nova.batch.imagecleanup.support.FakeS3;
import com.grandis.nova.batch.imagecleanup.support.MySqlTestContainer;
import com.grandis.nova.batch.imagecleanup.support.TestData.ImageRow;
import com.grandis.nova.common.jpa.StorageClock;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 실제 삭제(dry-run 끔)를 실제 DB · 가짜 S3 로 끝까지 돌린다. 청크를 2건으로 줄여 이어 읽기를 본다.
 * 정리 시계를 NOW 로 고정한다 — 기준 시각은 NOW − 24시간이다.
 */
@TestPropertySource(properties = {
        "nova.batch.image-cleanup.dry-run=false",
        "nova.batch.image-cleanup.chunk-size=2",
        "nova.upload.bucket=" + ImageCleanupTest.BUCKET,
        "nova.upload.region=ap-northeast-2"
})
@ExtendWith(OutputCaptureExtension.class)
class ImageCleanupTest extends ImageCleanupIntegrationTest {

    static final String BUCKET = "test-uploads";
    private static final FakeS3 S3 = FakeS3.start(BUCKET);
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final Instant CUTOFF = NOW.minus(Duration.ofHours(24));
    private static final Instant OLD = NOW.minus(Duration.ofHours(25));
    private static final Instant RECENT = NOW.minus(Duration.ofHours(23));

    @TestBean(name = "storageClock", methodName = "fixedClock")
    Clock storageClock;

    @Autowired
    ImageCleanupService service;

    @Autowired
    ImageCleanupStore store;

    static Clock fixedClock() {
        return StorageClock.atStorageResolution(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @DynamicPropertySource
    static void s3(DynamicPropertyRegistry registry) {
        registry.add("nova.upload.endpoint", S3::url);
    }

    @BeforeEach
    void resetS3() {
        S3.reset();
    }

    @Test
    void 기준_시간이_지난_UNUSED_사진은_행과_S3_객체를_지운다() {
        ImageRow product = data.image("products", "UNUSED", OLD);
        ImageRow promotion = data.image("promotions", "UNUSED", OLD);

        service.run();

        assertThat(exists(product)).isFalse();
        assertThat(exists(promotion)).isFalse();
        assertThat(S3.deleted()).containsExactlyInAnyOrder(product.key(), promotion.key());
        assertThat(pending()).isZero();
    }

    @Test
    void 기준_시간이_안_지났거나_USED_인_사진은_남긴다() {
        ImageRow recent = data.image("products", "UNUSED", RECENT);
        ImageRow atCutoff = data.image("products", "UNUSED", CUTOFF);
        ImageRow used = data.image("products", "USED", OLD);

        service.run();

        assertThat(exists(recent)).isTrue();
        assertThat(exists(atCutoff)).isTrue();
        assertThat(exists(used)).isTrue();
        assertThat(S3.requests()).isEmpty();
    }

    @Test
    void 드로우_회차가_가리키는_사진은_CDN_주소가_달라도_남긴다() {
        ImageRow drawn = data.image("products", "UNUSED", OLD);
        data.draw("폰", "https://old-cdn.example.com/" + drawn.key());
        ImageRow plain = data.image("products", "UNUSED", OLD);

        service.run();

        assertThat(exists(drawn)).isTrue();
        assertThat(exists(plain)).isFalse();
        assertThat(S3.deleted()).containsExactly(plain.key());
    }

    /** 주문 줄은 주문 때 옵션 사진 주소를 남긴다 — 상품 사진을 바꾼 뒤에도 지난 주문 상세가 그 사진을 보여 준다 */
    @Test
    void 지난_주문이_가리키는_사진은_CDN_주소가_달라도_남긴다() {
        ImageRow ordered = data.image("products", "UNUSED", OLD);
        data.orderItem("노트북", "https://old-cdn.example.com/" + ordered.key() + "?v=1");
        ImageRow plain = data.image("products", "UNUSED", OLD);

        service.run();

        assertThat(exists(ordered)).isTrue();
        assertThat(exists(plain)).isFalse();
        assertThat(S3.deleted()).containsExactly(plain.key());
    }

    /** catalog 는 새로 붙이는 주소에 쿼리 · 조각을 받지 않지만, 붙어 있어도 같은 객체라 지우지 않는다 */
    @Test
    void 드로우_주소에_쿼리나_조각이_붙어도_남긴다() {
        ImageRow withQuery = data.image("products", "UNUSED", OLD);
        data.draw("폰", "https://cdn.example.com/" + withQuery.key() + "?v=2");
        ImageRow withFragment = data.image("products", "UNUSED", OLD);
        data.draw("폰", "https://cdn.example.com/" + withFragment.key() + "#top");

        assertThat(store.deleteIfStillUnused(withQuery.id(), CUTOFF)).isFalse();
        assertThat(store.deleteIfStillUnused(withFragment.id(), CUTOFF)).isFalse();
        service.run();

        assertThat(exists(withQuery)).isTrue();
        assertThat(exists(withFragment)).isTrue();
        assertThat(S3.requests()).isEmpty();
    }

    /** 드로우가 가리키는 사진 3장이 오래된 쪽에서 청크(2건)를 채운다 — 처음부터 다시 고르면 뒤의 사진에 닿지 못하고 끝나지 않는다 */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void 못_지우는_사진이_청크보다_많아도_뒤의_사진을_지운다() {
        for (int hours = 3; hours >= 1; hours--) {
            ImageRow drawn = data.image("products", "UNUSED", OLD.minus(Duration.ofHours(hours)));
            data.draw("폰", "https://cdn.example.com/" + drawn.key());
        }
        ImageRow last = data.image("products", "UNUSED", OLD);

        service.run();

        assertThat(exists(last)).isFalse();
        assertThat(S3.deleted()).containsExactly(last.key());
        assertThat(count("SELECT COUNT(*) FROM images")).isEqualTo(3);
    }

    /** 한 저장이 뗀 사진 여러 장은 updated_at 이 같다. 청크(2건)가 그 사이에서 끝나도 나머지를 건너뛰지 않는다 */
    @Test
    void 같은_시각의_사진이_청크_경계에_걸려도_모두_지운다() {
        ImageRow first = data.image("products", "UNUSED", OLD);
        ImageRow second = data.image("products", "UNUSED", OLD);
        ImageRow third = data.image("products", "UNUSED", OLD);

        service.run();

        assertThat(S3.deleted()).containsExactlyInAnyOrder(first.key(), second.key(), third.key());
        assertThat(count("SELECT COUNT(*) FROM images")).isZero();
    }

    @Test
    void S3_삭제가_실패하면_삭제_대기로_남기고_다음_실행이_지운다() {
        ImageRow image = data.image("products", "UNUSED", OLD);
        S3.failDelete(image.key(), 500, S3ImageObjects.MAX_ATTEMPTS);

        service.run();

        assertThat(exists(image)).isFalse();
        assertThat(pendingKeys()).isEqualTo(image.key());
        assertThat(S3.requests()).hasSize(S3ImageObjects.MAX_ATTEMPTS).containsOnly(image.key());
        assertThat(S3.deleted()).isEmpty();

        service.run();

        assertThat(S3.deleted()).containsExactly(image.key());
        assertThat(pending()).isZero();
    }

    /** S3 가 멈췄으면 남은 대상도 실패한다 — 한 건마다 시간 상한까지 기다리지 않고 멈춰, 남은 사진은 다음 실행이 본다 */
    @Test
    void S3_삭제가_실패하면_이번_실행을_멈춘다() {
        ImageRow older = data.image("products", "UNUSED", OLD.minus(Duration.ofHours(1)));
        ImageRow newer = data.image("products", "UNUSED", OLD);
        S3.failDelete(older.key(), 500, S3ImageObjects.MAX_ATTEMPTS);

        service.run();

        assertThat(exists(older)).isFalse();
        assertThat(exists(newer)).isTrue();
        assertThat(S3.requests()).containsOnly(older.key());

        service.run();

        assertThat(S3.deleted()).containsExactly(older.key(), newer.key());
        assertThat(exists(newer)).isFalse();
        assertThat(pending()).isZero();
    }

    @Test
    void 지난_실행이_남긴_삭제_대기는_행이_없어도_S3_에서_지운다() {
        data.pendingDeletion("products/" + UUID.randomUUID() + ".jpg", NOW.minus(Duration.ofDays(1)));
        String key = pendingKeys();

        service.run();

        assertThat(S3.deleted()).containsExactly(key);
        assertThat(pending()).isZero();
    }

    /** 하루 한 번 돌므로 3일이면 세 번 연속 실패다 — 한 번 실패(경고)와 며칠째 막힌 정리(오류)를 로그로 가른다 */
    @Test
    void 삭제_대기가_3일_넘게_남으면_오류_로그를_남긴다(CapturedOutput output) {
        String stuck = "products/" + UUID.randomUUID() + ".jpg";
        data.pendingDeletion(stuck, NOW.minus(Duration.ofDays(4)));
        S3.failDelete(stuck, 500, S3ImageObjects.MAX_ATTEMPTS);

        service.run();

        assertThat(output).contains("사진 정리가 막혀 있다 — 삭제 대기 1건이 " + NOW.minus(Duration.ofDays(4)) + " 부터 남아 있다");
    }

    @Test
    void 삭제_대기가_3일_안이면_오류_로그를_남기지_않는다(CapturedOutput output) {
        String recent = "products/" + UUID.randomUUID() + ".jpg";
        data.pendingDeletion(recent, NOW.minus(Duration.ofDays(2)));
        S3.failDelete(recent, 500, S3ImageObjects.MAX_ATTEMPTS);

        service.run();

        assertThat(output).contains("그 사이 바뀌어 건너뜀 0 · 걸린 시간 ").contains("S3 삭제 실패로 멈춤").doesNotContain("사진 정리가 막혀 있다");
    }

    /** 고를 때 본 조건을 지울 때 다시 건다 — 그 사이 catalog 가 붙였거나 다시 뗐거나 드로우 · 주문이 가리키게 된 사진은 0행이다 */
    @Test
    void 고른_뒤_바뀐_사진은_행을_지우지_않는다() {
        ImageRow used = data.image("products", "USED", OLD);
        ImageRow detachedAgain = data.image("products", "UNUSED", RECENT);
        ImageRow drawn = data.image("products", "UNUSED", OLD);
        data.draw("폰", "https://cdn.example.com/" + drawn.key());
        ImageRow ordered = data.image("products", "UNUSED", OLD);
        data.orderItem("노트북", "https://cdn.example.com/" + ordered.key());
        ImageRow plain = data.image("products", "UNUSED", OLD);

        assertThat(store.deleteIfStillUnused(ordered.id(), CUTOFF)).isFalse();
        assertThat(store.deleteIfStillUnused(used.id(), CUTOFF)).isFalse();
        assertThat(store.deleteIfStillUnused(detachedAgain.id(), CUTOFF)).isFalse();
        assertThat(store.deleteIfStillUnused(drawn.id(), CUTOFF)).isFalse();
        assertThat(store.deleteIfStillUnused(plain.id(), CUTOFF)).isTrue();
        assertThat(count("SELECT COUNT(*) FROM images")).isEqualTo(4);
    }

    @Test
    void 다른_연결이_잠금을_쥐고_있으면_건너뛴다() throws Exception {
        ImageRow image = data.image("products", "UNUSED", OLD);
        MySQLContainer mysql = MySqlTestContainer.get();
        try (Connection other = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             Statement statement = other.createStatement()) {
            assertThat(scalar(statement, "SELECT GET_LOCK('" + ImageCleanupLock.NAME + "', 0)")).isEqualTo(1);

            service.run();

            assertThat(exists(image)).isTrue();
            assertThat(S3.requests()).isEmpty();
            statement.execute("SELECT RELEASE_LOCK('" + ImageCleanupLock.NAME + "')");
        }

        service.run();

        assertThat(exists(image)).isFalse();
    }

    @Test
    void 실행이_끝나면_잠금을_놓는다() {
        data.image("products", "UNUSED", OLD);

        service.run();

        assertThat(count("SELECT IS_FREE_LOCK('" + ImageCleanupLock.NAME + "')")).isEqualTo(1);
    }

    private boolean exists(ImageRow image) {
        return count("SELECT COUNT(*) FROM images WHERE id = UUID_TO_BIN('" + image.id() + "')") == 1;
    }

    private long pending() {
        return count("SELECT COUNT(*) FROM image_deletions");
    }

    private String pendingKeys() {
        return jdbc.sql("SELECT GROUP_CONCAT(object_key ORDER BY object_key) FROM image_deletions").query(String.class).single();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static int scalar(Statement statement, String sql) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
