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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 원장이 PK 중복으로 알린 동시 생성을 서비스가 새 트랜잭션에서 다시 해 성공시킨다. 각 요청이 커밋한다. */
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
        Long option = product.optionIds().getFirst();
        RacingWriter racing = new RacingWriter(writer, () -> transactionTemplate.executeWithoutResult(
                status -> writer.insert(option, 99, clock.instant())));
        AdminStockService service = new AdminStockService(new StockLedger(racing, clock), reader, catalog,
                transactionManager);

        StockResult result = service.set(product.productId(), List.of(new StockSetting(option, 10)));

        assertThat(racing.lockReads).hasValue(2);
        assertThat(result.created()).as("행은 끼어든 트랜잭션이 만들었다").isEmpty();
        assertThat(result.levels()).containsExactly(new StockLevel(option, 10, 0, 0));
    }

    /** 확률적이다 — 경합이 안 나도 통과한다. 다시 하기의 증명은 위 시험이다. */
    @Test
    void concurrentFirstPutsAllSucceedAndCreateEachRowOnce() throws Exception {
        StockProduct product = new OrderFixtures(jdbcTemplate).inStockProduct(2);
        List<Long> options = product.optionIds();

        List<Outcome<StockResult>> outcomes = Concurrently.run(REQUESTS, i -> () -> service.set(product.productId(),
                options.stream().map(option -> new StockSetting(option, 10 + i)).toList()));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        options.forEach(option -> assertThat(outcomes.stream().filter(o -> o.value().created().contains(option)))
                .as("옵션 %d 의 행을 만든 요청", option).hasSize(1));
        List<StockLevel> levels = reader.findByOptionIds(options);
        assertThat(levels).hasSize(options.size());
        assertThat(levels).extracting(StockLevel::total).containsOnly(levels.getFirst().total());
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
        public List<StockLevel> lockByOptionIds(Collection<Long> optionIds) {
            List<StockLevel> levels = delegate.lockByOptionIds(optionIds);
            if (lockReads.incrementAndGet() == 1) {
                // 다른 스레드 · 트랜잭션에서 커밋한다. 갭 잠금이 없으므로 기다리지 않는다.
                CompletableFuture.runAsync(race).orTimeout(10, TimeUnit.SECONDS).join();
            }
            return levels;
        }

        @Override
        public int changeTotal(Long optionId, int total, Instant now) {
            return delegate.changeTotal(optionId, total, now);
        }

        @Override
        public void insert(Long optionId, int total, Instant now) {
            delegate.insert(optionId, total, now);
        }
    }
}
