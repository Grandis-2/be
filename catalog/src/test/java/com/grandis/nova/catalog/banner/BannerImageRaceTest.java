package com.grandis.nova.catalog.banner;

import com.grandis.nova.catalog.image.ImageFormat;
import com.grandis.nova.catalog.image.ImageKeys;
import com.grandis.nova.catalog.image.ImagePurpose;
import com.grandis.nova.catalog.image.ImageStore;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ImageAttachmentsTestConfig;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.UuidBinary;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배너 지우기가 겹친 사진 교체와 엇갈릴 때 — 사진 붙이기 · 떼기는 진짜 구현이다. 고치기(사진 P → Q)를 커밋하지 않은 채 쥐고, 지우기가 잠금을
 * 기다리는 것을 본 뒤 커밋한다.
 */
@CatalogIntegrationTest
@TestPropertySource(properties = ImageAttachmentsTestConfig.REAL + "=true")
@DisplayName("배너 지우기 · 사진 교체가 겹칠 때(진짜 사진 구현)")
class BannerImageRaceTest {

    private static final String CDN = "https://img/";

    @Autowired BannerService service;
    @Autowired ImageStore store;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    UUID product;

    @BeforeEach
    void setUp() {
        ShopFixtures fixtures = new ShopFixtures(jdbcTemplate);
        jdbcTemplate.update("DELETE FROM banners");
        product = fixtures.product("IN_STOCK", "ACTIVE");
        fixtures.option(product, "ACTIVE", ShopFixtures.OPTION_PRICE);
        fixtures.stockReady(product);
    }

    @Test
    @DisplayName("지우기는 배너 행을 잠그고 읽는다 — 겹친 고치기가 사진을 P 에서 Q 로 바꿔 커밋하면 Q 를 떼어, 아무도 안 쓰는 USED 사진이 남지 않는다")
    void deleteDetachesTheImageACommittedUpdatePut() throws Exception {
        String p = uploaded();
        String q = uploaded();
        UUID banner = service.create(request(p)).bannerId();
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> update = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                service.update(banner, request(q));   // 이 트랜잭션에 합류 — 배너 행 · 사진 P · Q 를 쥔 채 커밋을 미룬다
                updated.countDown();
                await(release);
            }));
            assertThat(updated.await(30, TimeUnit.SECONDS)).isTrue();
            int waitsBefore = BannerRaceTest.lockWaits();
            Future<?> delete = pool.submit(() -> service.delete(banner));
            BannerRaceTest.awaitLockWaitsAbove(waitsBefore);
            release.countDown();
            update.get(30, TimeUnit.SECONDS);
            delete.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM banners WHERE id = ?", Integer.class, (Object) UuidBinary.toBytes(banner))).isZero();
        assertThat(status(p)).isEqualTo("UNUSED");
        assertThat(status(q)).as("고치기가 붙인 사진도 지우기가 뗀다").isEqualTo("UNUSED");
    }

    private String uploaded() {
        UUID id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        String key = ImageKeys.key(ImagePurpose.BANNER, id, ImageFormat.JPEG);
        store.insertUnused(id, key, Instant.now());
        return CDN + key;
    }

    private String status(String url) {
        UUID id = ImageKeys.idOf(ImageKeys.keyOf(url).orElseThrow()).orElseThrow();
        return jdbcTemplate.queryForObject("SELECT status FROM images WHERE id = ?", String.class, (Object) UuidBinary.toBytes(id));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private BannerRequest request(String imageUrl) {
        return new BannerRequest("사전예약", "배너", "설명", "탭", "Smartphone", imageUrl, "대체 글",
                new BannerRequest.Target(BannerTargetType.PRODUCT, product), true);
    }
}
