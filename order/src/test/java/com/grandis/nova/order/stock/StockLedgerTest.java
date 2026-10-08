package com.grandis.nova.order.stock;

import com.grandis.nova.order.stock.domain.exception.StockBelowCommittedException;
import com.grandis.nova.order.stock.domain.exception.StockBelowCommittedException.Shortfall;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 원장 한 번의 판정. 각 테스트는 롤백한다 — 롤백 뒤 "아무것도 안 바뀜" 은 AdminStockApiTest 가 커밋 경계로 본다. */
@OrderIntegrationTest
@Transactional
class StockLedgerTest {

    /** 저장 해상도(마이크로초)에 맞춘 고정 시각. */
    static final Instant FIXED = Instant.parse("2026-01-01T00:00:00.123456Z");

    @Autowired
    StockLedger ledger;

    @Autowired
    StockReader reader;

    @Autowired
    StockWriter writer;

    @Autowired
    JdbcTemplate jdbcTemplate;

    OrderFixtures fixtures;
    StockProduct product;
    UUID first;
    UUID second;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        product = fixtures.inStockProduct(2);
        first = product.optionIds().get(0);
        second = product.optionIds().get(1);
    }

    @Test
    void missingRowsAreCreatedWithNothingCommitted() {
        assertThat(ledger.set(List.of(new StockSetting(second, 7), new StockSetting(first, 0))))
                .containsExactlyInAnyOrder(first, second);

        assertThat(reader.findByOptionIds(List.of(first, second)))
                .containsExactly(new StockLevel(first, 0, 0, 0), new StockLevel(second, 7, 0, 0));
    }

    @Test
    void existingRowGetsNewTotalAndKeepsCommitted() {
        fixtures.stock(first, 10, 3, 2);

        assertThat(ledger.set(List.of(new StockSetting(first, 20)))).isEmpty();

        assertThat(reader.findByOptionIds(List.of(first))).containsExactly(new StockLevel(first, 20, 3, 2));
    }

    /**
     * 원장은 조건부 UPDATE 의 결과가 1 이 아니면 감소 한계로 본다. 값이 하나도 안 바뀌는 UPDATE(같은 총량 · 같은 시각)도 1 이어야
     * 한다 — 드라이버가 바뀐 행이 아니라 맞은 행을 세기 때문이다(Connector/J 기본 CLIENT_FOUND_ROWS). useAffectedRows=true 가
     * 되면 두 번째가 0 이 되어 여기서 깨진다. 시각을 고정해야 정말 같은 값이 된다(시계가 흐르면 updated_at 이 바뀌어 늘 1).
     */
    @Test
    void unchangedRowStillCountsAsMatched() {
        fixtures.stock(first, 10, 3, 2);

        assertThat(writer.changeTotal(first, 10, FIXED)).isEqualTo(1);
        assertThat(writer.changeTotal(first, 10, FIXED)).isEqualTo(1);

        StockLedger fixedClockLedger = new StockLedger(writer, reader, Clock.fixed(FIXED, ZoneOffset.UTC));
        fixedClockLedger.set(List.of(new StockSetting(first, 10)));
        assertThat(reader.findByOptionIds(List.of(first))).containsExactly(new StockLevel(first, 10, 3, 2));
    }

    @Test
    void totalCanDropExactlyToCommitted() {
        fixtures.stock(first, 10, 3, 2);

        ledger.set(List.of(new StockSetting(first, 5)));

        assertThat(reader.findByOptionIds(List.of(first))).containsExactly(new StockLevel(first, 5, 3, 2));
    }

    @Test
    void everyOptionBelowCommittedIsReported() {
        UUID third = fixtures.inStockProduct(1).optionIds().get(0);
        fixtures.stock(first, 10, 3, 2);
        fixtures.stock(second, 10, 0, 4);

        assertThatThrownBy(() -> ledger.set(List.of(
                new StockSetting(second, 3), new StockSetting(third, 1), new StockSetting(first, 4))))
                .isInstanceOfSatisfying(StockBelowCommittedException.class, e -> assertThat(e.shortfalls())
                        .containsExactly(new Shortfall(first, 5), new Shortfall(second, 4)));
    }

    @Test
    void initializeCreatesMissingAndLeavesExistingUntouched() {
        fixtures.stock(first, 10, 3, 2);

        assertThat(ledger.initialize(List.of(new StockSetting(first, 1), new StockSetting(second, 7))))
                .containsExactly(second);

        assertThat(reader.findByOptionIds(List.of(first, second)))
                .containsExactly(new StockLevel(first, 10, 3, 2), new StockLevel(second, 7, 0, 0));
    }

    @Test
    void duplicateOptionIsCallerBug() {
        assertThatThrownBy(() -> ledger.set(List.of(new StockSetting(first, 1), new StockSetting(first, 2))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
