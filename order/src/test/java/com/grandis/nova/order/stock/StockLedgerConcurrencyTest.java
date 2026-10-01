package com.grandis.nova.order.stock;

import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.order.stock.domain.exception.StockAlreadyCreatedException;
import com.grandis.nova.order.stock.domain.model.StockLevel;
import com.grandis.nova.order.stock.domain.model.StockSetting;
import com.grandis.nova.order.stock.domain.repository.StockReader;
import com.grandis.nova.order.stock.domain.repository.StockWriter;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderFixtures.StockProduct;
import com.grandis.nova.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 별도 트랜잭션에서 동시에 들어오는 재고 설정. 각 작업이 커밋한다. */
@OrderIntegrationTest
class StockLedgerConcurrencyTest {

    static final int REQUESTS = 6;

    @Autowired
    StockLedger ledger;

    @Autowired
    StockReader reader;

    @Autowired
    StockWriter writer;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    OrderFixtures fixtures;
    List<Long> options;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        StockProduct product = fixtures.inStockProduct(3);
        options = product.optionIds();
    }

    /**
     * 요청마다 옵션 순서를 뒤집어 보낸다. 원장이 option_id 오름차순으로 잠그므로 교착하지 않고,
     * 트랜잭션 하나가 세 행을 모두 잠근 채 바꾸므로 마지막 상태의 세 옵션은 같은 요청의 값이다.
     */
    @Test
    void concurrentSetsInAnyRequestOrderSerializeWithoutDeadlock() throws Exception {
        options.forEach(option -> fixtures.stock(option, 100, 1, 1));

        List<Outcome<Set<Long>>> outcomes = Concurrently.run(REQUESTS, i -> () -> transactionTemplate.execute(
                status -> ledger.set(settings(i % 2 == 0 ? options : options.reversed(), 10 + i))));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        List<StockLevel> levels = reader.findByOptionIds(options);
        assertThat(levels).extracting(StockLevel::total).containsOnly(levels.getFirst().total())
                .allSatisfy(total -> assertThat(total).isBetween(10, 10 + REQUESTS - 1));
        assertThat(levels).allSatisfy(level -> assertThat(level.committed()).isEqualTo(2));
    }

    /**
     * 설계의 전제를 결정적으로 본다: READ COMMITTED 에서 없는 행의 잠금 읽기는 갭을 잠그지 않는다.
     * T2 가 "없음" 을 잠가 읽은 채 열려 있어도 T1 의 삽입 · 커밋은 기다리지 않고(갭 잠금이 있으면 여기서 막혀 시간 초과),
     * 그 뒤 T2 의 삽입은 기다림 없이 PK 중복(StockAlreadyCreatedException)이다.
     */
    @Test
    void lockedReadOfMissingRowDoesNotBlockOthersAndLaterInsertIsDuplicate() {
        Long option = options.getFirst();

        transactionTemplate.executeWithoutResult(t2 -> {
            assertThat(writer.lockByOptionIds(List.of(option))).isEmpty();

            assertThat(CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(
                    t1 -> writer.insert(option, 5, Instant.now()))))
                    .as("T1 의 삽입은 T2 의 잠금 읽기를 기다리지 않는다")
                    .succeedsWithin(Duration.ofSeconds(10));

            assertThatThrownBy(() -> writer.insert(option, 7, Instant.now()))
                    .isInstanceOf(StockAlreadyCreatedException.class);
            t2.setRollbackOnly();
        });

        assertThat(reader.findByOptionIds(List.of(option))).extracting(StockLevel::total).containsExactly(5);
    }

    /**
     * 같은 옵션을 여럿이 동시에 처음 만든다. 확률적이다 — 경합이 안 나도 통과하므로 다시 하기의 증명은 아니다
     * (결정적인 증명은 위 시험과 AdminStockServiceConcurrencyTest). 경합이 나면 실패는 교착이 아니라 PK 중복뿐이어야 한다.
     */
    @Test
    void concurrentFirstCreationsSurfaceAsDuplicateKeyNotDeadlock() throws Exception {
        List<Outcome<Set<Long>>> outcomes = Concurrently.run(REQUESTS, i -> () -> transactionTemplate.execute(
                status -> ledger.set(settings(i % 2 == 0 ? options : options.reversed(), 10 + i))));

        assertThat(outcomes.stream().filter(Outcome::succeeded)).isNotEmpty();
        assertThat(outcomes.stream().filter(o -> !o.succeeded()))
                .allSatisfy(o -> assertThat(o.error()).isInstanceOf(StockAlreadyCreatedException.class));
        assertThat(outcomes.stream().filter(Outcome::succeeded).filter(o -> !o.value().isEmpty()))
                .as("행을 만든 요청은 하나다").hasSize(1);
        assertThat(reader.findByOptionIds(options)).hasSize(options.size());
    }

    /** 초기화는 있는 행을 건드리지 않으므로 잠그지도 않는다. 다른 트랜잭션이 그 행을 잡고 있어도 기다리지 않는다. */
    @Test
    void initializeDoesNotWaitForRowsItLeavesAlone() throws Exception {
        Long held = options.getFirst();
        fixtures.stock(held, 10, 0, 0);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(status -> {
            writer.lockByOptionIds(List.of(held));
            locked.countDown();
            awaitQuietly(release);
        }));
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(CompletableFuture.supplyAsync(() -> transactionTemplate.execute(
                    status -> ledger.initialize(settings(options, 5)))))
                    .succeedsWithin(Duration.ofSeconds(5))
                    .isEqualTo(Set.copyOf(options.subList(1, options.size())));
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void refusesToRunWithoutTransaction() {
        assertThatThrownBy(() -> ledger.set(settings(options, 1)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<StockSetting> settings(List<Long> optionIds, int total) {
        return optionIds.stream().map(optionId -> new StockSetting(optionId, total)).toList();
    }
}
