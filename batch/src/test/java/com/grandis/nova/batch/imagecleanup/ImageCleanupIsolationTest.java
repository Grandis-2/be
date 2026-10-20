package com.grandis.nova.batch.imagecleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.grandis.nova.batch.support.BatchIntegrationTest;
import com.grandis.nova.batch.support.FakeS3;
import com.grandis.nova.batch.support.TestData.ImageRow;
import com.grandis.nova.common.jpa.StorageClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 운영 설정에서 연결 격리 수준(hikari.transaction-isolation)을 빠뜨려 MySQL 기본값 REPEATABLE READ 로 돌 때를 흉내 낸다.
 * REPEATABLE READ 면 행 삭제의 하위 조회가 훑은 주문 줄과 그 사이(갭)를 커밋까지 잠가, 정리 트랜잭션 동안 새 주문 줄 넣기가 기다린다.
 * 행 삭제 트랜잭션이 READ COMMITTED 를 직접 걸어 새 주문을 막지 않는지 본다 — 트랜잭션 안에서 시계를 읽는 순간(삭제 대기 넣기)에
 * 다른 연결로 주문을 넣어 본다.
 */
@TestPropertySource(properties = {
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ",
        "nova.batch.image-cleanup.dry-run=false",
        "nova.upload.bucket=" + ImageCleanupIsolationTest.BUCKET,
        "nova.upload.region=ap-northeast-2"
})
class ImageCleanupIsolationTest extends BatchIntegrationTest {

    static final String BUCKET = "test-uploads";
    private static final FakeS3 S3 = FakeS3.start(BUCKET);
    /** 트랜잭션 안에서 시계를 처음 읽을 때 한 번 돈다 */
    private static final AtomicReference<Runnable> INSIDE_TRANSACTION = new AtomicReference<>();

    @TestBean(name = "storageClock", methodName = "hookedClock")
    Clock storageClock;

    @Autowired
    ImageCleanupService service;

    static Clock hookedClock() {
        return StorageClock.atStorageResolution(new HookedClock());
    }

    @DynamicPropertySource
    static void s3(DynamicPropertyRegistry registry) {
        registry.add("nova.upload.endpoint", S3::url);
    }

    @BeforeEach
    void resetS3() {
        S3.reset();
        INSIDE_TRANSACTION.set(null);
    }

    @Test
    void 연결_기본값이_REPEATABLE_READ_여도_정리_트랜잭션이_새_주문을_막지_않는다() throws Exception {
        ImageRow image = data.image("products", "UNUSED", Instant.now().minus(Duration.ofHours(25)));
        data.orderItem("노트북", "https://cdn.example.com/products/other.jpg");
        AtomicReference<CompletableFuture<Void>> ordering = new AtomicReference<>();
        AtomicReference<Boolean> waited = new AtomicReference<>();
        INSIDE_TRANSACTION.set(() -> {
            // 정리 트랜잭션이 이 스레드에서 커밋을 기다리는 동안 다른 연결이 새 주문 줄을 넣는다
            ordering.set(CompletableFuture.runAsync(() -> data.orderItem("노트북", "https://cdn.example.com/products/new.jpg")));
            try {
                ordering.get().get(5, TimeUnit.SECONDS);
                waited.set(false);
            } catch (TimeoutException e) {
                waited.set(true);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        service.run();
        ordering.get().get(30, TimeUnit.SECONDS);

        assertThat(waited.get()).as("정리 트랜잭션 동안 새 주문 줄 넣기가 기다렸는지").isFalse();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM order_items").query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM images").query(Long.class).single()).isZero();
        assertThat(S3.deleted()).containsExactly(image.key());
    }

    /** 지금 시각을 주고, 트랜잭션 안에서 처음 읽힐 때 걸어 둔 일을 돌린다 */
    private static final class HookedClock extends Clock {

        @Override
        public Instant instant() {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                Runnable hook = INSIDE_TRANSACTION.getAndSet(null);
                if (hook != null) {
                    hook.run();
                }
            }
            return Instant.now();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }
}
