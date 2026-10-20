package com.grandis.nova.catalog.image;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("사진 업로드 서비스 — 동시 처리 허가 · 실패 갈래")
class ImageUploadServiceTest {

    private static final ImageUploadProperties PROPERTIES = new ImageUploadProperties("bucket", URI.create("https://img"), "ap-northeast-2", null,
            "test", "test", Duration.ofSeconds(10), Duration.ofSeconds(25));
    private static final Instant NOW = Instant.parse("2026-10-15T03:00:00Z");

    /** 넣은 행만 기억하는 표. */
    static final class RecordingStore extends ImageStore {
        final List<String> inserted = new ArrayList<>();

        RecordingStore() {
            super(null);
        }

        @Override
        public synchronized void insertUnused(UUID id, String objectKey, Instant now) {
            inserted.add(objectKey);
        }
    }

    /** put 을 막아 두거나 실패시키는 저장소. */
    static final class ControlledStorage extends ImageStorage {
        volatile CountDownLatch entered = new CountDownLatch(0);
        volatile CountDownLatch release = new CountDownLatch(0);
        volatile RuntimeException failure;
        final List<String> puts = new ArrayList<>();

        ControlledStorage() {
            super(null, PROPERTIES);
        }

        @Override
        void put(String key, ImageFormat format, byte[] bytes) {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (failure != null) {
                throw failure;
            }
            synchronized (puts) {
                puts.add(key);
            }
        }
    }

    private final RecordingStore store = new RecordingStore();
    private final ControlledStorage storage = new ControlledStorage();
    private final ImageUploadService service = new ImageUploadService(new ImageProcessor(), store, storage, PROPERTIES,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static MockMultipartFile png() {
        return new MockMultipartFile("file", "image", "image/png", ImageFixtures.png(8, 8));
    }

    @Test
    @DisplayName("허가 두 장이 S3 전송 중이면 셋째는 기다리지 않고 UploadBusyException(Retry-After 2초) — 행도 만들지 않는다. 전송이 끝나면 다시 받는다")
    void thirdConcurrentUploadIsBusy() throws Exception {
        storage.entered = new CountDownLatch(ImageUploadService.MAX_CONCURRENT);
        storage.release = new CountDownLatch(1);
        List<CompletableFuture<String>> running = new ArrayList<>();
        for (int i = 0; i < ImageUploadService.MAX_CONCURRENT; i++) {
            running.add(CompletableFuture.supplyAsync(() -> service.upload(ImagePurpose.PRODUCT, png())));
        }
        assertThat(storage.entered.await(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.upload(ImagePurpose.PRODUCT, png()))
                .isInstanceOfSatisfying(UploadBusyException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(2));
        assertThat(store.inserted).hasSize(ImageUploadService.MAX_CONCURRENT);

        storage.release.countDown();
        for (CompletableFuture<String> f : running) {
            assertThat(f.get(10, TimeUnit.SECONDS)).startsWith("https://img/products/");
        }
        assertThat(service.upload(ImagePurpose.PRODUCT, png())).as("허가가 돌아왔다").startsWith("https://img/products/");
    }

    @Test
    @DisplayName("S3 가 실패하면 503 — 행은 먼저 넣어 남기고(배치가 지운다) 주소는 주지 않는다. 실패해도 허가를 돌려준다")
    void storageFailureIs503AndReleasesPermit() {
        storage.failure = SdkClientException.create("timeout");
        for (int i = 0; i < ImageUploadService.MAX_CONCURRENT + 1; i++) {
            assertThatThrownBy(() -> service.upload(ImagePurpose.PROMOTION, png()))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(CommonErrorCode.DEPENDENCY_UNAVAILABLE));
        }
        assertThat(store.inserted).hasSize(ImageUploadService.MAX_CONCURRENT + 1).allMatch(k -> k.startsWith("promotions/"));
        storage.failure = null;
        assertThat(service.upload(ImagePurpose.PRODUCT, png())).as("실패한 셋 뒤에도 허가가 남아 있다").startsWith("https://img/products/");
    }

    @Test
    @DisplayName("검사에 걸린 파일은 허가 · 행 · S3 어느 것도 쓰지 않는다")
    void invalidFileTouchesNothing() {
        assertThatThrownBy(() -> service.upload(ImagePurpose.PRODUCT, new MockMultipartFile("file", "x".getBytes())))
                .isInstanceOf(BusinessException.class);
        assertThat(store.inserted).isEmpty();
        assertThat(storage.puts).isEmpty();
    }
}
