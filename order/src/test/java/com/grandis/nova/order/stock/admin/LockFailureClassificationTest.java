package com.grandis.nova.order.stock.admin;

import com.grandis.nova.order.stock.domain.repository.StockWriter;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 서비스가 다시 할지 가르는 기준({@link AdminStockService#isDeadlock})을 MySQL 의 진짜 오류로 본다.
 * 재고 쓰기의 두 경로 — 잠금 읽기 · 조건부 UPDATE(JPA 네이티브 쿼리)와 INSERT(persist + flush) — 에서 올라온 예외 모두
 * 교착(1213)과 잠금 대기 초과(1205)가 갈려야 한다.
 */
@OrderIntegrationTest
class LockFailureClassificationTest {

    @Autowired
    StockWriter writer;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    List<Long> options;
    List<Long> unstocked;

    @BeforeEach
    void setUp() {
        OrderFixtures fixtures = new OrderFixtures(jdbcTemplate);
        options = fixtures.inStockProduct(2).optionIds();
        options.forEach(option -> fixtures.stock(option, 10, 0, 0));
        unstocked = fixtures.inStockProduct(2).optionIds();
    }

    @Test
    void lockWaitTimeoutIsNotDeadlock() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(status -> {
            writer.lockByOptionIds(List.of(options.getFirst()));
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        Throwable failure;
        try {
            failure = transactionTemplate.execute(status -> {
                // 연결을 풀에 돌려주기 전에 되돌린다 — 세션 변수는 연결에 남는다
                jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = 1");
                try {
                    return catchThrowable(() -> writer.changeTotal(options.getFirst(), 20, Instant.now()));
                } finally {
                    jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = DEFAULT");
                }
            });
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }

        assertThat(failure).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(vendorCode(failure)).isEqualTo(1205);
        assertThat(AdminStockService.isDeadlock(failure)).isFalse();
    }

    /** INSERT 의 FK 검사는 product_options 부모 행에 S 잠금을 잡는다. 그 행을 다른 트랜잭션이 X 로 쥐고 있으면 기다리다 1205 다. */
    @Test
    void insertWaitingOnParentRowTimesOutAsNonDeadlock() throws Exception {
        Long option = unstocked.getFirst();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.queryForList("SELECT id FROM product_options WHERE id = ? FOR UPDATE", option);
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        Throwable failure;
        try {
            failure = transactionTemplate.execute(status -> {
                jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = 1");
                try {
                    // 실패한 엔티티가 영속성 컨텍스트에 남아 커밋하면 다시 flush 된다. 운영 코드는 예외를 올려 롤백한다
                    status.setRollbackOnly();
                    return catchThrowable(() -> writer.insert(option, 1, Instant.now()));
                } finally {
                    jdbcTemplate.execute("SET SESSION innodb_lock_wait_timeout = DEFAULT");
                }
            });
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }

        assertThat(failure).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(vendorCode(failure)).isEqualTo(1205);
        assertThat(AdminStockService.isDeadlock(failure)).isFalse();
    }

    /** 서로 상대가 아직 커밋하지 않은 옵션을 삽입하면 PK 중복 확인끼리 기다려 교착한다. 진 쪽은 PK 중복이 아니라 1213 이다. */
    @Test
    void crossedInsertsAreDeadlockNotDuplicate() {
        CyclicBarrier bothInsertedFirst = new CyclicBarrier(2);
        List<CompletableFuture<Throwable>> sides = List.of(
                crossInsert(unstocked.get(0), unstocked.get(1), bothInsertedFirst),
                crossInsert(unstocked.get(1), unstocked.get(0), bothInsertedFirst));

        List<Throwable> failures = sides.stream().map(side -> side.orTimeout(30, TimeUnit.SECONDS).join())
                .filter(failure -> failure != null).toList();

        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst()).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(vendorCode(failures.getFirst())).isEqualTo(1213);
        assertThat(AdminStockService.isDeadlock(failures.getFirst())).isTrue();
    }

    @Test
    void crossLockingIsDeadlock() throws Exception {
        CyclicBarrier bothLockedFirst = new CyclicBarrier(2);
        List<CompletableFuture<Throwable>> sides = List.of(
                crossLock(options.get(0), options.get(1), bothLockedFirst),
                crossLock(options.get(1), options.get(0), bothLockedFirst));

        List<Throwable> failures = sides.stream().map(side -> side.orTimeout(30, TimeUnit.SECONDS).join())
                .filter(failure -> failure != null).toList();

        assertThat(failures).hasSize(1);
        assertThat(failures.getFirst()).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(vendorCode(failures.getFirst())).isEqualTo(1213);
        assertThat(AdminStockService.isDeadlock(failures.getFirst())).isTrue();
    }

    /** first 를 잠그고, 상대도 잠근 뒤 second 를 잠근다. 진 쪽의 예외를 돌려준다. */
    private CompletableFuture<Throwable> crossLock(Long first, Long second, CyclicBarrier bothLockedFirst) {
        return CompletableFuture.supplyAsync(() -> catchThrowable(() -> transactionTemplate.executeWithoutResult(status -> {
            writer.lockByOptionIds(List.of(first));
            await(bothLockedFirst);
            writer.lockByOptionIds(List.of(second));
        })));
    }

    private CompletableFuture<Throwable> crossInsert(Long first, Long second, CyclicBarrier bothInsertedFirst) {
        return CompletableFuture.supplyAsync(() -> catchThrowable(() -> transactionTemplate.executeWithoutResult(status -> {
            writer.insert(first, 1, Instant.now());
            await(bothInsertedFirst);
            writer.insert(second, 1, Instant.now());
        })));
    }

    private static int vendorCode(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getErrorCode();
            }
        }
        return -1;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
