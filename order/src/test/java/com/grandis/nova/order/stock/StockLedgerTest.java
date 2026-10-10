package com.grandis.nova.order.stock;

import com.grandis.nova.order.stock.domain.exception.StockBelowCommittedException;
import com.grandis.nova.order.stock.domain.exception.StockBelowCommittedException.Shortfall;
import com.grandis.nova.order.stock.domain.exception.StockShortageException;
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
import java.util.Map;
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

    /** 가용(총량 − 확보 − 판매)이 수량과 같아도 확보된다 — 경계에서 하나 모자라면 0행이다. */
    @Test
    void reserveAddsToReservedUpToExactlyAvailable() {
        fixtures.stock(first, 10, 3, 2);
        fixtures.stock(second, 4, 0, 0);

        ledger.reserve(Map.of(first, 5, second, 1));

        assertThat(reader.findByOptionIds(List.of(first, second)))
                .containsExactly(new StockLevel(first, 10, 8, 2), new StockLevel(second, 4, 1, 0));
        assertThatThrownBy(() -> ledger.reserve(Map.of(first, 1)))
                .isInstanceOfSatisfying(StockShortageException.class, e -> assertThat(e.optionIds()).containsExactly(first));
    }

    /** 모자란 옵션 · 재고 행이 없는 옵션을 모두 담아 던진다. 되돌리는 것은 호출자의 롤백이다(주문 생성 시험이 커밋 경계로 본다). */
    @Test
    void shortageListsEveryOptionThatCouldNotBeReserved() {
        UUID missing = fixtures.inStockProduct(1).optionIds().getFirst();
        fixtures.stock(first, 1, 0, 0);
        fixtures.stock(second, 5, 0, 0);

        assertThatThrownBy(() -> ledger.reserve(Map.of(first, 2, second, 1, missing, 1)))
                .isInstanceOfSatisfying(StockShortageException.class,
                        e -> assertThat(e.optionIds()).containsExactlyInAnyOrder(first, missing));
    }


    /** 반환과 확보를 옵션별 증감으로 합친다 — 순증은 조건부 확보, 순감은 반환, 0 은 그대로. 순증이 모자라면 모은다. */
    @Test
    void releaseAndReserveAppliesNetChangePerOption() {
        UUID third = fixtures.inStockProduct(1).optionIds().getFirst();
        fixtures.stock(first, 10, 4, 0);
        fixtures.stock(second, 10, 3, 0);
        fixtures.stock(third, 10, 2, 0);

        ledger.releaseAndReserve(Map.of(first, 3, second, 2), Map.of(first, 1, second, 2, third, 5));

        assertThat(reader.findByOptionIds(List.of(first, second, third))).containsExactlyInAnyOrder(
                new StockLevel(first, 10, 2, 0), new StockLevel(second, 10, 3, 0), new StockLevel(third, 10, 7, 0));
        assertThatThrownBy(() -> ledger.releaseAndReserve(Map.of(), Map.of(third, 4)))
                .isInstanceOfSatisfying(StockShortageException.class, e -> assertThat(e.optionIds()).containsExactly(third));
    }

    /** 순감을 되돌릴 확보가 모자라면 데이터 어긋남(ISE). 0 · 음수 수량은 호출하는 코드의 잘못(IAE). */
    @Test
    void releaseAndReserveRejectsMissingReservationAndNonPositiveQuantities() {
        fixtures.stock(first, 10, 1, 0);

        assertThatThrownBy(() -> ledger.releaseAndReserve(Map.of(first, 2), Map.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ledger.releaseAndReserve(Map.of(first, 0), Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.releaseAndReserve(Map.of(), Map.of(first, -1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(reader.findByOptionIds(List.of(first))).containsExactly(new StockLevel(first, 10, 1, 0));
    }

}
