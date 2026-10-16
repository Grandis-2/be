package com.grandis.nova.order.stock.reconcile;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.cart.domain.repository.CartStore;
import com.grandis.nova.order.client.catalog.CatalogClient;
import com.grandis.nova.order.client.catalog.CatalogOption;
import com.grandis.nova.order.client.catalog.CatalogOptions;
import com.grandis.nova.order.client.payment.DeclineReason;
import com.grandis.nova.order.draw.AdminDrawService;
import com.grandis.nova.order.draw.DrawSettlement;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.cancel.UnpaidCartOrderRelease;
import com.grandis.nova.order.order.command.PlaceOrderCommand;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.pay.PaymentResults;
import com.grandis.nova.order.order.pay.PaymentSettlement;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.stock.StockLedger;
import com.grandis.nova.order.stock.domain.model.StockBalance;
import com.grandis.nova.order.stock.domain.model.StockReconcileRun;
import com.grandis.nova.order.stock.domain.repository.ReconcileLock;
import com.grandis.nova.order.stock.domain.repository.StockExpectations;
import com.grandis.nova.order.stock.domain.repository.StockReconcileRuns;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.MutableClock;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.TestAuth;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.MeterRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.UUID;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 재고 장부 대조. 실제 흐름(장바구니 주문 생성 · 교체 · 사용자 취소 · 만료 · 결제 거절/승인, 바로 구매 주문 생성 · 결제 승인 · 사용자 취소 · 만료,
 * 드로우 회차 생성 · 전원/일부 당첨 반영)은 어긋남을
 * 남기지 않고, SQL 로 심은 어긋남 · 재고 행 없는 옵션은 잡는다. 시험끼리 DB 를 함께 쓰므로(다른 시험이 일부러 어긋난 장부를 심는다) 결과는
 * 이 시험이 만든 옵션으로만 본다.
 * 대역 구성은 CartOrderApiTest 와 같다 — Spring 컨텍스트를 함께 쓴다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
class StockReconcilerTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final BigDecimal PRICE = new BigDecimal("1250000");
    static final String SHIP_TO = """
            {"name":"홍길동","phone":"010-0000-0000","postalCode":"04524","line1":"서울시 중구 세종대로 110"}""";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired StockReconciler reconciler;
    @Autowired StockGauges gauges;
    @Autowired StockExpectations expectations;
    @Autowired StockReconcileRuns reconcileRuns;
    @Autowired ReconcileLock lock;
    @Autowired Clock clock;
    @Autowired MeterRegistry registry;
    @Autowired OrderLedger orderLedger;
    @Autowired StockLedger stockLedger;
    @Autowired UnpaidCartOrderRelease release;
    @Autowired PaymentResults payments;
    @Autowired AdminDrawService draws;
    @Autowired DrawSettlement drawSettlement;
    @MockitoBean CatalogClient catalogClient;
    /** 쓰지 않지만 CartOrderApiTest 와 같은 대역 구성으로 두어 Spring 컨텍스트를 함께 쓴다. */
    @MockitoSpyBean CartStore store;

    OrderFixtures fixtures;
    TransactionTemplate tx;
    Map<UUID, CatalogOption> catalog = new HashMap<>();

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        tx = new TransactionTemplate(transactionManager);
        given(catalogClient.getOptions(any(), any())).willAnswer(invocation -> {
            Collection<UUID> ids = invocation.getArgument(0);
            return ApiResponse.ok(new CatalogOptions(ids.stream().filter(catalog::containsKey).map(catalog::get).toList()));
        });
    }

    @Test
    @DisplayName("주문 · 결제 · 취소 · 드로우의 실제 흐름은 장부를 주문 기준과 같게 둔다 — 대조가 이 옵션들을 어긋남으로 보지 않는다")
    void realFlowsLeaveNoDrift() throws Exception {
        UUID phone = sellable(20);
        UUID watch = sellable(20);
        UUID gift = sellable(20);
        UUID tablet = sellable(20);

        // 교체: 같은 회원의 새 장바구니 주문이 결제 안 된 앞 주문을 취소하고 반환 + 새 확보를 한 번에 한다
        UUID buyer = fixtures.customer();
        cartLine(buyer, phone, 2);
        cartLine(buyer, watch, 1);
        String replaced = placeCartOrder(buyer, phone, 2, watch, 1);
        jdbcTemplate.update("UPDATE cart_items SET quantity = 3 WHERE customer_id = ? AND option_id = ?", bytes(buyer), bytes(phone));
        String paid = placeCartOrder(buyer, phone, 3, watch, 1);
        assertThat(paid).isNotEqualTo(replaced);
        // 결제 거절(확보를 쥔 채 결제 대기로) → 다시 결제 승인(확보 → 판매)
        settle(paid, PaymentSettlement.Result.DECLINED);
        settle(paid, PaymentSettlement.Result.APPROVED);

        // 사용자 취소
        UUID canceler = fixtures.customer();
        cartLine(canceler, phone, 1);
        String canceled = placeCartOrder(canceler, phone, 1, null, 0);
        mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", canceled).with(TestAuth.customer(canceler))).andExpect(status().isOk());

        // 결제 기한 만료(CartOrderExpiry 가 주문마다 하는 것과 같다) · 결제 대기로 남는 주문
        UUID late = fixtures.customer();
        cartLine(late, watch, 2);
        String expired = placeCartOrder(late, watch, 2, null, 0);
        tx.executeWithoutResult(status -> release.cancel(UUID.fromString(expired), EventCause.system("PAYMENT_EXPIRED")));
        UUID waiting = fixtures.customer();
        cartLine(waiting, watch, 4);
        placeCartOrder(waiting, watch, 4, null, 0);

        // 바로 구매: 결제 승인(확보 → 판매) · 사용자 취소 · 만료 · 결제 대기로 남는 주문 — 장바구니를 거치지 않는다
        UUID direct = fixtures.customer();
        settle(placeBuyNow(direct, tablet, 2), PaymentSettlement.Result.APPROVED);
        mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", placeBuyNow(direct, tablet, 1)).with(TestAuth.customer(direct)))
                .andExpect(status().isOk());
        String directExpired = placeBuyNow(direct, tablet, 3);
        tx.executeWithoutResult(status -> release.cancel(UUID.fromString(directExpired), EventCause.system("PAYMENT_EXPIRED")));
        placeBuyNow(direct, tablet, 1);

        // 드로우: 전원 당첨(남은 확보 반납) · 일부 당첨(추첨 요청 → 결과) · 아직 마감 전(확보 유지)
        UUID allWin = createDraw(gift, 3);
        paidEntry(allWin);
        paidEntry(allWin);
        close(allWin);
        assertThat(drawSettlement.close(allWin)).isTrue();
        UUID partial = createDraw(gift, 1);
        UUID winner = paidEntry(partial);
        paidEntry(partial);
        paidEntry(partial);
        close(partial);
        assertThat(drawSettlement.close(partial)).as("추첨 요청").isTrue();
        drawSettlement.settle(partial, List.of(winner));
        createDraw(gift, 2);

        assertThat(stockOf(phone)).as("교체로 반환된 2 · 판매 3 · 취소로 반환된 1").containsExactly(20, 0, 3);
        assertThat(stockOf(watch)).as("판매 1 · 만료로 반환된 2 · 결제 대기 4").containsExactly(20, 4, 1);
        assertThat(stockOf(gift)).as("당첨 2 + 1 판매 · 마감 전 회차 2 확보").containsExactly(20, 2, 3);
        assertThat(stockOf(tablet)).as("판매 2 · 취소 1 · 만료 3 반환 · 결제 대기 1").containsExactly(20, 1, 2);

        reconciler.reconcile().orElseThrow();

        assertThat(expectations.findDrifted(Integer.MAX_VALUE).first()).extracting(StockBalance::optionId).doesNotContain(phone, watch, gift, tablet);
        for (UUID option : List.of(phone, watch, gift, tablet)) {
            assertThat(expectations.balanceOf(option).matches()).as("미리보기도 같다: " + option).isTrue();
        }
    }

    @Test
    @DisplayName("SQL 로 고친 장부 · 재고 행 없는 옵션의 주문을 잡아 옵션마다 기대 · 실제를 남기고, 결과를 기록해 지표가 읽는다")
    void detectsDriftRecordsRunAndFeedsGauges() {
        UUID touched = fixtures.inStockProduct(1).optionIds().getFirst();
        fixtures.stock(touched, 10, 0, 0);
        place(touched, 2, true);
        jdbcTemplate.update("UPDATE option_inventories SET stock_reserved = 5, stock_sold = 1 WHERE option_id = ?", (Object) bytes(touched));
        OrderFixtures.StockProduct unregisteredProduct = fixtures.inStockProduct(1);
        UUID unregistered = unregisteredProduct.optionIds().getFirst();
        place(unregistered, 3, false);
        UUID clean = fixtures.inStockProduct(1).optionIds().getFirst();
        fixtures.stock(clean, 10, 0, 0);
        place(clean, 4, true);

        StockReconcileRun run = reconciler.reconcile().orElseThrow();

        // 시험끼리 DB 를 함께 써 어긋난 옵션이 기록 상한을 넘을 수 있다 — 옵션별 판정은 전체 목록으로, 기록은 상한 · 수의 맞물림으로 본다
        StockExpectations.Drifts all = expectations.findDrifted(Integer.MAX_VALUE);
        Map<UUID, StockBalance> drifts = new HashMap<>();
        all.first().forEach(drift -> drifts.put(drift.optionId(), drift));
        assertThat(drifts.get(touched)).isEqualTo(new StockBalance(touched, drifts.get(touched).productId(), true, 10, 5, 1, 2, 0));
        assertThat(drifts.get(unregistered)).isEqualTo(new StockBalance(unregistered, unregisteredProduct.productId(), false, 0, 0, 0, 3, 0));
        assertThat(drifts).doesNotContainKey(clean);
        assertThat(run.driftOptions()).isGreaterThanOrEqualTo(2);
        assertThat(run.drifts()).hasSize(Math.min(run.driftOptions(), StockReconcileRun.DETAIL_LIMIT));
        assertThat(run.truncated()).isEqualTo(run.driftOptions() > StockReconcileRun.DETAIL_LIMIT);
        StockExpectations.Drifts one = expectations.findDrifted(1);
        assertThat(one.total()).as("수는 LIMIT 전에 센다").isEqualTo(all.total()).isEqualTo(all.first().size());
        assertThat(one.first()).as("앞 limit 개만 가져온다").containsExactly(all.first().getFirst());
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT drift_options, detail FROM stock_reconcile_runs ORDER BY finished_at DESC LIMIT 1");
        assertThat(((Number) row.get("drift_options")).intValue()).isEqualTo(run.driftOptions());
        assertThat(JSON.readTree((String) row.get("detail")).get("options")).hasSize(run.drifts().size());

        gauges.refresh();

        assertThat(registry.get("order.stock.drift.options").gauge().value()).isEqualTo(run.driftOptions());
        assertThat(registry.get("order.stock.reconcile.last.success.timestamp").gauge().value())
                .isCloseTo(run.finishedAt().toEpochMilli() / 1000.0, within(0.002));
    }

    @Test
    @DisplayName("끝난 시각은 표를 다 읽은 뒤의 시각이다 — 성공 시각 지표 · 주기 실행의 간격 판정이 이것을 본다")
    void finishedAtIsTakenAfterTheScan() {
        // 다른 시험의 '마지막 대조'를 가리지 않게 지난 시각으로
        Instant start = Instant.now().minus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        MutableClock clock = new MutableClock(start);
        StockExpectations slow = new StockExpectations() {
            @Override
            public Drifts findDrifted(int limit) {
                clock.advance(Duration.ofSeconds(90));
                return new Drifts(0, List.of());
            }

            @Override
            public StockBalance balanceOf(UUID optionId) {
                throw new UnsupportedOperationException();
            }
        };

        StockReconcileRun run = new StockReconciler(slow, reconcileRuns, lock, transactionManager, clock).reconcile().orElseThrow();

        assertThat(run.startedAt()).isEqualTo(start);
        assertThat(run.finishedAt()).isEqualTo(start.plusSeconds(90));
    }

    @Test
    @DisplayName("표 읽기가 실패하면 기록을 남기지 않고 잠금을 놓는다 — 다음 대조는 돈다")
    void failedScanLeavesNoRunAndReleasesTheLock() {
        long runs = runCount();
        StockExpectations failing = new StockExpectations() {
            @Override
            public Drifts findDrifted(int limit) {
                throw new IllegalStateException("문장 시간 상한 초과");
            }

            @Override
            public StockBalance balanceOf(UUID optionId) {
                throw new UnsupportedOperationException();
            }
        };

        assertThatThrownBy(() -> new StockReconciler(failing, reconcileRuns, lock, transactionManager, clock).reconcile())
                .hasMessageContaining("문장 시간 상한 초과");

        assertThat(runCount()).isEqualTo(runs);
        assertThat(jdbcTemplate.queryForObject("SELECT IS_FREE_LOCK(CONCAT(DATABASE(), '.order-stock-reconcile'))", Integer.class))
                .isEqualTo(1);
        assertThat(reconciler.reconcile()).as("다음 대조는 돈다").isPresent();
        assertThat(runCount()).isEqualTo(runs + 1);
    }

    @Test
    @DisplayName("다른 인스턴스(연결)가 대조 잠금을 쥐고 있으면 기다리지 않고 건너뛴다 — 기록을 남기지 않는다")
    void skipsWhileAnotherInstanceHoldsTheLock() throws Exception {
        long runs = runCount();
        try (Connection other = dataSource.getConnection(); Statement statement = other.createStatement()) {
            try (ResultSet got = statement.executeQuery("SELECT GET_LOCK(CONCAT(DATABASE(), '.order-stock-reconcile'), 0)")) {
                got.next();
                assertThat(got.getInt(1)).isEqualTo(1);
            }
            try {
                assertThat(reconciler.reconcile()).isEmpty();
            } finally {
                statement.execute("SELECT RELEASE_LOCK(CONCAT(DATABASE(), '.order-stock-reconcile'))");
            }
        }
        assertThat(runCount()).isEqualTo(runs);

        assertThat(reconciler.reconcile()).as("놓은 뒤에는 돈다").isPresent();
        assertThat(runCount()).isEqualTo(runs + 1);
    }

    @Test
    @DisplayName("잠금은 기록이 커밋된 뒤에 놓는다 — 그 사이 다른 인스턴스(연결)는 잠금을 못 잡고, 기록은 이미 다른 연결에서 보인다")
    void holdsTheLockUntilTheRunIsCommitted() {
        long runs = runCount();
        Integer[] freeAfterCommit = new Integer[1];
        long[] visibleAfterCommit = new long[1];
        ReconcileLock probing = new ReconcileLock() {
            @Override
            public <T> Optional<T> runExclusively(Supplier<T> work) {
                return lock.runExclusively(() -> {
                    T result = work.get();
                    // 일(트랜잭션)은 끝났고 잠금은 아직 쥔 순간 — 트랜잭션 밖이라 jdbcTemplate 은 풀의 다른 연결을 쓴다
                    freeAfterCommit[0] = jdbcTemplate.queryForObject("SELECT IS_FREE_LOCK(CONCAT(DATABASE(), '.order-stock-reconcile'))",
                            Integer.class);
                    visibleAfterCommit[0] = runCount();
                    return result;
                });
            }
        };

        new StockReconciler(expectations, reconcileRuns, probing, transactionManager, clock).reconcile().orElseThrow();

        assertThat(freeAfterCommit[0]).as("커밋 뒤에도 쥐고 있다").isZero();
        assertThat(visibleAfterCommit[0]).as("놓기 전에 이미 커밋됐다").isEqualTo(runs + 1);
        assertThat(jdbcTemplate.queryForObject("SELECT IS_FREE_LOCK(CONCAT(DATABASE(), '.order-stock-reconcile'))", Integer.class))
                .as("끝나면 놓는다").isEqualTo(1);
    }

    @Test
    @DisplayName("일하는 동안 잠금 연결이 끊기면(DB 순단) 그 연결을 풀에서 내보낸다 — 풀 슬롯이 새지 않고, 잠금은 서버가 풀어 다음 대조가 돈다")
    void evictsTheLockConnectionWhenItDies() throws Exception {
        HikariPoolMXBean poolStats = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        int activeBefore = poolStats.getActiveConnections();

        Optional<Integer> result = lock.runExclusively(() -> {
            // 잠금을 쥔 세션을 다른 연결에서 끊는다 — 놓기가 실패한다
            Long holder = jdbcTemplate.queryForObject("SELECT IS_USED_LOCK(CONCAT(DATABASE(), '.order-stock-reconcile'))", Long.class);
            jdbcTemplate.execute("KILL CONNECTION " + holder);
            return 1;
        });

        assertThat(result).contains(1);
        assertThat(poolStats.getActiveConnections()).as("빌린 연결이 모두 돌아왔다").isEqualTo(activeBefore);
        // KILL 은 비동기라 서버가 세션을 정리하며 잠금을 풀 때까지 잠깐 걸릴 수 있다
        Awaitility.await("끊긴 세션의 잠금 풀림").atMost(Duration.ofSeconds(5)).until(() -> Integer.valueOf(1).equals(
                jdbcTemplate.queryForObject("SELECT IS_FREE_LOCK(CONCAT(DATABASE(), '.order-stock-reconcile'))", Integer.class)));
        assertThat(reconciler.reconcile()).as("다음 대조는 돈다").isPresent();
    }

    @Test
    @DisplayName("트랜잭션 안에서 잠그면 거절한다 — 일의 커밋이 잠금을 놓은 뒤로 밀리는 것을 막는다")
    void refusesToLockInsideATransaction() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> lock.runExclusively(() -> 1)))
                .hasMessageContaining("트랜잭션 밖에서 잡는다");
        assertThat(jdbcTemplate.queryForObject("SELECT IS_FREE_LOCK(CONCAT(DATABASE(), '.order-stock-reconcile'))", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("주기 실행은 두 시간 안에 끝난 대조가 있으면 건너뛴다 — 인스턴스마다 발화가 조금 늦어도, 4시가 성공한 날의 5시 재시도도 돌지 않는다")
    void scheduledRunSkipsWhenARecentRunExists() {
        reconciler.reconcile().orElseThrow();
        long runs = runCount();

        assertThat(reconciler.run(StockReconciler.SCHEDULED_GAP)).isEmpty();
        assertThat(runCount()).isEqualTo(runs);
    }

    private long runCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_reconcile_runs", Long.class);
    }

    /** 장바구니 주문 하나를 원장으로 바로 만든다. reserve=false 면 확보 없이 — 재고 행 없는 옵션의 주문처럼 장부 밖에서 생긴 주문을 흉내 낸다. */
    private void place(UUID option, int quantity, boolean reserve) {
        UUID productId = jdbcTemplate.queryForObject("SELECT BIN_TO_UUID(product_id) FROM product_options WHERE id = ?",
                (rs, n) -> UUID.fromString(rs.getString(1)), (Object) bytes(option));
        PlaceOrderCommand command = new PlaceOrderCommand(fixtures.customer(), OrderSource.CART, null, null, null, OrderFixtures.ADDRESS,
                List.of(new PlaceOrderCommand.Line(productId, option, quantity, PRICE, 0, BigDecimal.ZERO, "아이폰 17", "블랙")));
        tx.executeWithoutResult(status -> {
            if (reserve) {
                stockLedger.reserve(Map.of(option, quantity));
            }
            orderLedger.place(command.toDraft(), EventCause.user());
        });
    }

    /** 결제 요청(승인 중) → 결과 반영. 승인 중 전이는 재고를 옮기지 않는다. */
    private void settle(String orderId, PaymentSettlement.Result result) {
        UUID id = UUID.fromString(orderId);
        String window = UUID.randomUUID().toString();
        tx.executeWithoutResult(status -> orderLedger.requestPayment(id, window, EventCause.user()));
        BigDecimal amount = jdbcTemplate.queryForObject("SELECT total_amount FROM orders WHERE id = ?", BigDecimal.class, (Object) bytes(id));
        boolean approved = result == PaymentSettlement.Result.APPROVED;
        payments.settle(new PaymentSettlement(id, window, result, amount, approved ? clock.instant() : null,
                approved ? null : DeclineReason.CARD_REJECTED));
    }

    private String placeCartOrder(UUID customer, UUID first, int firstQuantity, UUID second, int secondQuantity) throws Exception {
        String items = item(first, firstQuantity) + (second == null ? "" : "," + item(second, secondQuantity));
        String body = mockMvc.perform(post("/api/v1/orders").with(TestAuth.customer(customer)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"CART","items":[%s],"shipTo":%s}""".formatted(items, SHIP_TO)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("data").get("orderId").asString();
    }

    /** 바로 구매 주문 하나 — 누름마다 새 키. */
    private String placeBuyNow(UUID customer, UUID option, int quantity) throws Exception {
        String body = mockMvc.perform(post("/api/v1/orders").with(TestAuth.customer(customer)).contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content("""
                                {"source":"BUY_NOW","items":[%s],"shipTo":%s}""".formatted(item(option, quantity), SHIP_TO)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("data").get("orderId").asString();
    }

    private static String item(UUID option, int quantity) {
        return """
                {"variantId":"%s","quantity":%d,"expectedUnitPrice":1250000}""".formatted(option, quantity);
    }

    private void cartLine(UUID customer, UUID option, int quantity) {
        jdbcTemplate.update("""
                INSERT INTO cart_items (id, customer_id, option_id, warranty_selected, quantity, created_at, updated_at)
                VALUES (?, ?, ?, 0, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bytes(UUID.randomUUID()), bytes(customer), bytes(option), quantity);
    }

    /** 일반 판매 · 판매 중 · 재고 등록을 마친 옵션(장바구니 주문 · 드로우 증정이 된다). */
    private UUID sellable(int stock) {
        OrderFixtures.StockProduct product = fixtures.inStockProduct(1);
        UUID option = product.optionIds().getFirst();
        fixtures.stock(option, stock, 0, 0);
        catalog.put(option, new CatalogOption(option, product.productId(), "아이폰 17", "블랙 / 256GB", "SKU-" + option, PRICE,
                "ACTIVE", "IN_STOCK", "ACTIVE", true, true, new CatalogOption.Warranty(false, BigDecimal.ZERO), null));
        return option;
    }

    /** 관리자 회차 만들기(당첨 인원만큼 확보). 마감은 미래여야 해서 한 시간 뒤로 만든다. */
    private UUID createDraw(UUID option, int winners) {
        Instant now = Instant.now();
        return draws.create("session", UUID.randomUUID().toString(), new AdminDrawService.CreateDraw(catalog.get(option).productId(), option,
                "드로우", new BigDecimal("100"), winners, now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.HOURS))).campaign().id();
    }

    /** 마감을 지나게 한다(시계 차 여유 30초보다 앞으로). */
    private void close(UUID draw) {
        jdbcTemplate.update("UPDATE draw_campaigns SET closes_at = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE WHERE id = ?", (Object) bytes(draw));
    }

    private UUID paidEntry(UUID draw) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO draw_entries (id, campaign_id, customer_id, status, ship_to_name, ship_to_phone, ship_to_postal_code, ship_to_line1,
                                          created_at, updated_at)
                VALUES (?, ?, ?, 'PAID', '당첨자', '010-0000-0000', '04524', '서울시 중구', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                bytes(id), bytes(draw), bytes(fixtures.customer()));
        return id;
    }

    /** [총량, 확보, 판매] */
    private List<Integer> stockOf(UUID option) {
        return jdbcTemplate.queryForObject("SELECT stock_total, stock_reserved, stock_sold FROM option_inventories WHERE option_id = ?",
                (rs, n) -> List.of(rs.getInt(1), rs.getInt(2), rs.getInt(3)), (Object) bytes(option));
    }
}
