package com.grandis.nova.order.stock.admin;

import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.order.stock.StockLedger;
import com.grandis.nova.order.stock.domain.model.StockLevel;
import com.grandis.nova.order.stock.domain.model.StockSetting;
import com.grandis.nova.order.stock.domain.repository.CatalogOptions;
import com.grandis.nova.order.stock.domain.repository.StockReader;
import com.grandis.nova.order.stock.domain.repository.StockWriter;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderFixtures.StockProduct;
import com.grandis.nova.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 원장이 PK 중복으로 알린 동시 생성을 서비스가 새 트랜잭션에서 다시 해 성공시킨다. 설정 · 초기화 모두. 각 요청이 커밋한다. */
@OrderIntegrationTest
class AdminStockServiceConcurrencyTest {

    static final int REQUESTS = 6;

    @Autowired
    AdminStockService service;

    @Autowired
    StockReader reader;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StockWriter writer;

    @Autowired
    CatalogOptions catalog;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    Clock clock;

    /**
     * 다시 하기를 결정적으로 본다. 첫 시도의 잠금 읽기("없음") 직후에 다른 트랜잭션이 같은 옵션의 행을 만들어 커밋하게 해,
     * 첫 시도는 반드시 PK 중복으로 지고 둘째 시도는 그 행을 "있는 행"으로 잡아 설정한다.
     */
    @Test
    void firstAttemptLosingTheCreationRaceIsRetriedAndSucceeds() {
        StockProduct product = new OrderFixtures(jdbcTemplate).inStockProduct(1);
        UUID option = product.optionIds().getFirst();
        RacingWriter racing = new RacingWriter(writer, () -> transactionTemplate.executeWithoutResult(
                status -> writer.insert(option, 99, clock.instant())));
        AdminStockService service = new AdminStockService(new StockLedger(racing, reader, clock), reader, catalog,
                transactionManager);

        StockResult result = service.set(product.productId(), List.of(new StockSetting(option, 10)));

        assertThat(racing.lockReads).hasValue(2);
        assertThat(result.created()).as("행은 끼어든 트랜잭션이 만들었다").isEmpty();
        assertThat(result.levels()).containsExactly(new StockLevel(option, 10, 0, 0));
    }

    /**
     * 초기화의 다시 하기와 덮어쓰지 않음을 함께 본다. 첫 시도가 "없음" 을 읽은 직후 다른 트랜잭션이 99 로 만들어 커밋하면,
     * 첫 시도는 PK 중복으로 지고 둘째 시도는 그 행을 있는 행으로 보고 건드리지 않는다 — 요청의 10 이 아니라 99 가 남는다.
     */
    @Test
    void initializeLosingTheCreationRaceKeepsTheWinnersValue() {
        StockProduct product = new OrderFixtures(jdbcTemplate).inStockProduct(1);
        UUID option = product.optionIds().getFirst();
        RacingReader racing = new RacingReader(reader, () -> transactionTemplate.executeWithoutResult(
                status -> writer.insert(option, 99, clock.instant())));
        AdminStockService service = new AdminStockService(new StockLedger(writer, racing, clock), reader, catalog,
                transactionManager);

        StockResult result = service.initialize(product.productId(), List.of(new StockSetting(option, 10)));

        assertThat(racing.reads).hasValue(2);
        assertThat(result.created()).isEmpty();
        assertThat(result.levels()).containsExactly(new StockLevel(option, 99, 0, 0));
    }

    /** 확률적이다 — 경합이 안 나도 통과한다. 다시 하기의 증명은 위 두 시험이다. */
    @Test
    void concurrentFirstPutsAllSucceedAndCreateEachRowOnce() throws Exception {
        StockProduct product = new OrderFixtures(jdbcTemplate).inStockProduct(2);
        List<UUID> options = product.optionIds();

        List<Outcome<StockResult>> outcomes = Concurrently.run(REQUESTS, i -> () -> service.set(product.productId(),
                options.stream().map(option -> new StockSetting(option, 10 + i)).toList()));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        options.forEach(option -> assertThat(outcomes.stream().filter(o -> o.value().created().contains(option)))
                .as("옵션 %d 의 행을 만든 요청", option).hasSize(1));
        List<StockLevel> levels = reader.findByOptionIds(options);
        assertThat(levels).hasSize(options.size());
        assertThat(levels).extracting(StockLevel::total).containsOnly(levels.getFirst().total());
    }

    // 초기화는 덮어쓰지 않으므로 남는 값은 행을 만든 요청의 값이다. 확률적이다 — 다시 하기의 증명은 위 시험이다.
    @Test
    void concurrentFirstInitializationsCreateOnceAndKeepTheCreatorsValue() throws Exception {
        StockProduct product = new OrderFixtures(jdbcTemplate).inStockProduct(2);
        List<UUID> options = product.optionIds();

        List<Outcome<StockResult>> outcomes = Concurrently.run(REQUESTS, i -> () -> service.initialize(
                product.productId(), options.stream().map(option -> new StockSetting(option, 10 + i)).toList()));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        List<StockResult> creators = outcomes.stream().map(Outcome::value).filter(r -> !r.created().isEmpty()).toList();
        assertThat(creators).hasSize(1);
        assertThat(creators.getFirst().created()).containsExactlyInAnyOrderElementsOf(options);
        assertThat(reader.findByOptionIds(options)).isEqualTo(creators.getFirst().levels());
        assertThat(outcomes).allSatisfy(o -> assertThat(o.value().levels()).isEqualTo(creators.getFirst().levels()));
    }

    /** 첫 잠금 읽기 직후에 한 번 race 를 실행하는 쓰기 포트. 나머지는 그대로 넘긴다. */
    static final class RacingWriter implements StockWriter {

        final StockWriter delegate;
        final Runnable race;
        final AtomicInteger lockReads = new AtomicInteger();

        RacingWriter(StockWriter delegate, Runnable race) {
            this.delegate = delegate;
            this.race = race;
        }

        @Override
        public List<StockLevel> lockByOptionIds(Collection<UUID> optionIds) {
            List<StockLevel> levels = delegate.lockByOptionIds(optionIds);
            if (lockReads.incrementAndGet() == 1) {
                // 다른 스레드 · 트랜잭션에서 커밋한다. 갭 잠금이 없으므로 기다리지 않는다.
                CompletableFuture.runAsync(race).orTimeout(10, TimeUnit.SECONDS).join();
            }
            return levels;
        }

        @Override
        public int changeTotal(UUID optionId, int total, Instant now) {
            return delegate.changeTotal(optionId, total, now);
        }

        @Override
        public int reserve(UUID optionId, int quantity, Instant now) {
            return delegate.reserve(optionId, quantity, now);
        }

        @Override
        public void insert(UUID optionId, int total, Instant now) {
            delegate.insert(optionId, total, now);
        }
    }

    /** 첫 읽기 직후에 한 번 race 를 실행하는 읽기 포트(원장의 초기화가 쓰는 쪽). */
    static final class RacingReader implements StockReader {

        final StockReader delegate;
        final Runnable race;
        final AtomicInteger reads = new AtomicInteger();

        RacingReader(StockReader delegate, Runnable race) {
            this.delegate = delegate;
            this.race = race;
        }

        @Override
        public List<StockLevel> findByOptionIds(Collection<UUID> optionIds) {
            List<StockLevel> levels = delegate.findByOptionIds(optionIds);
            if (reads.incrementAndGet() == 1) {
                CompletableFuture.runAsync(race).orTimeout(10, TimeUnit.SECONDS).join();
            }
            return levels;
        }
    }
}
