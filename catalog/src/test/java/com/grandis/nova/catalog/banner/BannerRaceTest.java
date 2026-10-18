package com.grandis.nova.catalog.banner;

import com.grandis.nova.catalog.CatalogErrorCode;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.MySqlContainerConfig;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.UuidBinary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 배너 고치기 · 지우기가 겹친 작업과 엇갈릴 때 — 두 트랜잭션을 실제 커넥션 둘로 태운다. 한쪽이 모든 배너를 잠근 채, 다른 쪽이 잠금을 기다리는 것
 * (performance_schema.data_lock_waits, root 연결)을 본 뒤 커밋한다.
 */
@CatalogIntegrationTest
@DisplayName("배너 고치기 · 순서 바꾸기가 겹칠 때")
class BannerRaceTest {

    @Autowired BannerService service;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    ShopFixtures fixtures;
    UUID product;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        jdbcTemplate.update("DELETE FROM banners");
        product = fixtures.product("IN_STOCK", "ACTIVE");
        fixtures.option(product, "ACTIVE", ShopFixtures.OPTION_PRICE);
        fixtures.stockReady(product);
    }

    @Test
    @DisplayName("고치기는 배너 행을 잠그고 읽는다 — 겹친 순서 바꾸기가 먼저 커밋하면 그 번호 위에서 고쳐, 순서가 되돌아가거나 번호가 겹치지 않는다")
    void updateDoesNotUndoConcurrentReorder() throws Exception {
        UUID a = service.create(request("a")).bannerId();
        UUID b = service.create(request("b")).bannerId();
        CountDownLatch locked = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> reorder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                int waitsBefore = lockWaits();
                jdbcTemplate.queryForList("SELECT id FROM banners FOR UPDATE");
                locked.countDown();
                awaitLockWaitsAbove(waitsBefore);
                jdbcTemplate.update("UPDATE banners SET position = CASE id WHEN ? THEN 1 ELSE 0 END", (Object) UuidBinary.toBytes(a));
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
            Future<?> update = pool.submit(() -> service.update(a, request("a 고침")));
            reorder.get(30, TimeUnit.SECONDS);
            update.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(position(a)).as("순서 바꾸기가 a 를 뒤로 보냈다").isEqualTo(1);
        assertThat(position(b)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM banners WHERE id = ?", String.class, (Object) UuidBinary.toBytes(a)))
                .isEqualTo("a 고침");
    }

    @Test
    @DisplayName("만들기의 대상 검사는 저장과 같은 트랜잭션에서 상품을 공유 잠금으로 읽는다 — 겹친 상품 삭제가 먼저 커밋하면 지워진 상품에 배너가 붙지 않고 400")
    void createSeesConcurrentProductDelete() throws Exception {
        CountDownLatch deleting = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> create;
        try {
            Future<?> delete = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                int waitsBefore = lockWaits();
                jdbcTemplate.update("UPDATE products SET visible = 0, deleted_at = UTC_TIMESTAMP(6) WHERE id = ?", (Object) UuidBinary.toBytes(product));
                deleting.countDown();
                awaitLockWaitsAbove(waitsBefore);
            }));
            assertThat(deleting.await(30, TimeUnit.SECONDS)).isTrue();
            create = pool.submit(() -> service.create(request("지워지는 상품")));
            delete.get(30, TimeUnit.SECONDS);
            Future<?> created = create;
            assertThatThrownBy(() -> created.get(30, TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class)
                    .cause().satisfies(e -> assertThat(((BusinessException) e).errorCode().name()).isEqualTo("VALIDATION_FAILED"));
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM banners", Integer.class)).isZero();
    }

    @Test
    @DisplayName("잠금 대기가 넘치면 409 STATE_CONFLICT · retryable — 500 이 아니다(잠금 실패의 응답 매핑을 본다 — 어느 문장에서 기다리는지는 위 시험이 본다)")
    void lockWaitTimeoutIsRetryableConflict() throws Exception {
        UUID a = service.create(request("a")).bannerId();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbcTemplate.queryForList("SELECT id FROM banners FOR UPDATE");
                locked.countDown();
                await(done);
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
            // 고치기는 이 트랜잭션에 합류한다 — 같은 커넥션에 잠금 대기 1초를 걸고, 풀에 돌려주기 전에 되돌린다
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = 1");
                try {
                    assertThatThrownBy(() -> service.update(a, request("a 고침"))).isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(CatalogErrorCode.STATE_CONFLICT);
                        assertThat(e.details()).containsEntry("retryable", true);
                    });
                } finally {
                    jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = DEFAULT");
                    status.setRollbackOnly();
                }
            });
            done.countDown();
            holder.get(30, TimeUnit.SECONDS);
        } finally {
            done.countDown();
            pool.shutdownNow();
        }
    }

    private int position(UUID banner) {
        return jdbcTemplate.queryForObject("SELECT position FROM banners WHERE id = ?", Integer.class, (Object) UuidBinary.toBytes(banner));
    }

    /**
     * 서버 전체의 잠금 대기 수(performance_schema.data_lock_waits) — 앱 계정은 권한이 없어 root 로 본다. information_schema.innodb_trx 의
     * LOCK WAIT 는 쓰지 않는다: 실측(2026-10-19) 고치기가 FOR UPDATE 에서 기다리는(processlist state=statistics, data_locks 7행) 동안에도
     * innodb_trx 가 0행으로 나온 적이 있다.
     */
    static int lockWaits() {
        MySQLContainer mysql = MySqlContainerConfig.get();
        try (Connection root = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             Statement statement = root.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM performance_schema.data_lock_waits")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 대기 수가 baseline 을 넘을 때까지 — 앞 시험이 남긴 서버 쪽 대기에 속지 않게 시작 전 수와 견준다. */
    static void awaitLockWaitsAbove(int baseline) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (lockWaits() <= baseline) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("잠금을 기다리지 않았다");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private BannerRequest request(String title) {
        return new BannerRequest("사전예약", title, "설명", "탭", "Smartphone", "https://img/banner.png", "대체 글",
                new BannerRequest.Target(BannerTargetType.PRODUCT, product), true);
    }
}
