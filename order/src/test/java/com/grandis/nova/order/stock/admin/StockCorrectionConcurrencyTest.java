package com.grandis.nova.order.stock.admin;

import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.cancel.UnpaidCartOrderRelease;
import com.grandis.nova.order.order.command.PlaceOrderCommand;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.pay.PaymentResults;
import com.grandis.nova.order.order.pay.PaymentSettlement;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.stock.StockLedger;
import com.grandis.nova.order.stock.domain.model.StockBalance;
import com.grandis.nova.order.stock.domain.model.StockCorrection;
import com.grandis.nova.order.stock.domain.repository.CatalogOptions;
import com.grandis.nova.order.stock.domain.repository.StockAdjustments;
import com.grandis.nova.order.stock.domain.repository.StockExpectations;
import com.grandis.nova.order.stock.domain.repository.StockReader;
import com.grandis.nova.order.stock.domain.repository.StockWriter;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보정과 재고를 옮기는 트랜잭션이 겹쳐도 끝에 장부가 주문 기준과 같다. 결정적으로 본다 — 보정이 재고 행을 잠근 뒤 주문 기준을 읽기 직전에
 * 취소 · 결제 승인을 시작하고, 그 트랜잭션이 주문 행을 바꾼 채 재고 행에서 기다리는 것을 확인한 뒤 읽는다. 보정은 커밋 안 된 전이를 보지 않고
 * 덮고, 기다리던 쪽의 증감이 그 위에 얹힌다.
 */
@OrderIntegrationTest
class StockCorrectionConcurrencyTest {

    static final BigDecimal PRICE = new BigDecimal("1250000");

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired OrderLedger orderLedger;
    @Autowired StockLedger stockLedger;
    @Autowired UnpaidCartOrderRelease release;
    @Autowired PaymentResults payments;
    @Autowired StockWriter writer;
    @Autowired StockReader reader;
    @Autowired StockExpectations expectations;
    @Autowired StockAdjustments adjustments;
    @Autowired CatalogOptions catalog;
    @Autowired Clock clock;

    OrderFixtures fixtures;
    TransactionTemplate tx;
    OrderFixtures.StockProduct product;
    UUID option;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        tx = new TransactionTemplate(transactionManager);
        product = fixtures.inStockProduct(1);
        option = product.optionIds().getFirst();
        fixtures.stock(option, 10, 0, 0);
    }

    @Test
    @DisplayName("보정이 재고 행을 쥔 동안 미결제 취소가 반환을 기다리면 — 보정은 그 주문의 확보를 남기고, 취소의 반환이 그 위에서 빠진다")
    void correctionWhileUnpaidCancelWaits() {
        Order order = place(2);
        jdbcTemplate.update("UPDATE option_inventories SET stock_reserved = 5 WHERE option_id = ?", (Object) bytes(option));
        RacingExpectations racing = new RacingExpectations(expectations,
                () -> tx.execute(status -> release.cancel(order.id(), EventCause.user()).applied()),
                "UPDATE option_inventories SET stock_reserved = stock_reserved -%");

        StockCorrection correction = service(racing).correct(product.productId(), option, "보정", "admin");

        assertThat(racing.raced()).as("취소").isEqualTo(true);
        assertThat(correction.afterReserved()).as("커밋 안 된 취소는 보지 않는다").isEqualTo(2);
        assertThat(stock()).containsExactly(10, 0, 0);
        assertThat(expectations.balanceOf(option).matches()).isTrue();
    }

    @Test
    @DisplayName("보정이 재고 행을 쥔 동안 결제 승인이 판매 확정을 기다리면 — 보정은 확보로 덮고, 승인의 확보 → 판매가 그 위에서 옮긴다")
    void correctionWhilePaymentApprovalWaits() {
        Order order = place(2);
        String window = UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> orderLedger.requestPayment(order.id(), window, EventCause.user()));
        jdbcTemplate.update("UPDATE option_inventories SET stock_reserved = 5, stock_sold = 1 WHERE option_id = ?", (Object) bytes(option));
        RacingExpectations racing = new RacingExpectations(expectations,
                () -> payments.settle(new PaymentSettlement(order.id(), window, PaymentSettlement.Result.APPROVED, order.totalAmount().amount(),
                        clock.instant(), null)).applied(),
                "UPDATE option_inventories%stock_sold = stock_sold +%");

        StockCorrection correction = service(racing).correct(product.productId(), option, "보정", "admin");

        assertThat(racing.raced()).as("승인 반영").isEqualTo(true);
        assertThat(correction.afterReserved()).isEqualTo(2);
        assertThat(correction.afterSold()).isZero();
        assertThat(stock()).containsExactly(10, 0, 2);
        assertThat(expectations.balanceOf(option).matches()).isTrue();
    }

    /** 원장의 주문 기준 읽기만 끼어드는 포트로 바꾼 서비스. 미리보기 · 기록 · 대상 확인은 그대로다. */
    private StockCorrectionService service(StockExpectations racing) {
        return new StockCorrectionService(new StockLedger(writer, reader, racing, clock), expectations, adjustments, catalog,
                transactionManager, clock);
    }

    private Order place(int quantity) {
        PlaceOrderCommand command = new PlaceOrderCommand(fixtures.customer(), OrderSource.CART, null, null, null, OrderFixtures.ADDRESS,
                List.of(new PlaceOrderCommand.Line(product.productId(), option, quantity, PRICE, 0, BigDecimal.ZERO, "아이폰 17", "블랙")));
        return tx.execute(status -> {
            stockLedger.reserve(Map.of(option, quantity));
            return orderLedger.place(command.toDraft(), EventCause.user());
        });
    }

    /** [총량, 확보, 판매] */
    private List<Integer> stock() {
        return jdbcTemplate.queryForObject("SELECT stock_total, stock_reserved, stock_sold FROM option_inventories WHERE option_id = ?",
                (rs, n) -> List.of(rs.getInt(1), rs.getInt(2), rs.getInt(3)), (Object) bytes(option));
    }

    /**
     * 첫 옵션 읽기 직전에 다른 스레드에서 race 를 시작하고, 그 트랜잭션의 재고 UPDATE(waitingFor 모양)가 실행 중 — 잠금에서 기다리는 중 — 일
     * 때까지 본 뒤 읽는다. 잠금이 없으면 마이크로초에 끝나는 문장이라 보인다면 기다리는 중이다(CartOrderApiTest 와 같은 계기).
     */
    final class RacingExpectations implements StockExpectations {

        final StockExpectations delegate;
        final Supplier<Boolean> race;
        final String waitingFor;
        final AtomicReference<CompletableFuture<Boolean>> running = new AtomicReference<>();

        RacingExpectations(StockExpectations delegate, Supplier<Boolean> race, String waitingFor) {
            this.delegate = delegate;
            this.race = race;
            this.waitingFor = waitingFor;
        }

        @Override
        public Drifts findDrifted(int limit) {
            return delegate.findDrifted(limit);
        }

        @Override
        public StockBalance balanceOf(UUID optionId) {
            if (running.compareAndSet(null, CompletableFuture.supplyAsync(race))) {
                Awaitility.await("기다리는 재고 UPDATE 가 없다: " + waitingFor).atMost(Duration.ofSeconds(10)).until(() -> {
                    Long count = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE COMMAND = 'Query' AND INFO LIKE ?", Long.class, waitingFor);
                    return count != null && count > 0;
                });
            }
            return delegate.balanceOf(optionId);
        }

        /** race 의 결과. 보정이 커밋한 뒤라 곧 끝난다. */
        Boolean raced() {
            return running.get().orTimeout(20, TimeUnit.SECONDS).join();
        }
    }
}
