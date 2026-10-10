package com.grandis.nova.order.draw;

import com.grandis.nova.order.event.OrderEventDispatcher;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.TestAuth;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 추첨할 차례 · 추첨 요청 · 결과 반영(당첨 0원 주문 · 재고). 회차 · 응모 · 재고는 표에 직접 심고, 주기 작업은 직접 부른다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
class DrawSettlementTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DrawCloser closer;
    @Autowired DrawSettlement settlement;
    @Autowired OrderEventDispatcher dispatcher;
    @Autowired MockMvc mockMvc;

    OrderFixtures fixtures;
    UUID product;
    UUID option;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        OrderFixtures.StockProduct stocked = fixtures.inStockProduct(1);
        product = stocked.productId();
        option = stocked.optionIds().getFirst();
    }

    // ── 추첨할 차례 ──

    @Test
    @DisplayName("결제 완료가 당첨 인원 이하면 추첨 없이 전원 당첨 — 0원 주문 · 당첨분 판매 확정 · 남은 확보 반납, 다시 돌아도 한 번")
    void fewerPaidThanWinnersAllWin() {
        UUID draw = closedDraw(3);
        UUID first = entry(draw, "PAID", null);
        UUID second = entry(draw, "PAID", null);
        UUID unpaid = entry(draw, "AWAITING_PAYMENT", null);

        assertThat(closer.closeDue()).isGreaterThanOrEqualTo(1);
        closer.closeDue();

        assertThat(wonOrder(first)).isNotNull();
        assertThat(wonOrder(second)).isNotNull();
        assertThat(wonOrder(unpaid)).isNull();
        Map<String, Object> order = jdbcTemplate.queryForMap("""
                SELECT source, status, total_amount, payment_due_at, ship_to_name FROM orders WHERE id = ?""", (Object) wonOrder(first));
        assertThat(order).containsEntry("source", "DRAW").containsEntry("status", "AWAITING_CONFIRMATION").containsEntry("payment_due_at", null)
                .containsEntry("ship_to_name", "당첨자");
        assertThat(((Number) order.get("total_amount")).intValue()).isZero();
        Map<String, Object> item = jdbcTemplate.queryForMap("""
                SELECT option_id, quantity, unit_price_snapshot, product_title_snapshot FROM order_items WHERE order_id = ?""",
                (Object) wonOrder(first));
        assertThat(item.get("option_id")).isEqualTo(bytes(option));
        assertThat(((Number) item.get("quantity")).intValue()).isEqualTo(1);
        assertThat(((Number) item.get("unit_price_snapshot")).intValue()).isZero();
        assertThat(item).containsEntry("product_title_snapshot", "증정품");
        assertThat(stock()).containsEntry("stock_reserved", 0).containsEntry("stock_sold", 2);
        assertThat(campaign(draw).get("settled_at")).isNotNull();
        assertThat(campaign(draw).get("draw_requested_at")).isNull();
        assertThat(outboxCount(draw, "DRAW_REQUESTED")).isZero();
        assertThat(orderCount(draw)).isEqualTo(2);
    }

    @Test
    @DisplayName("결제 완료가 당첨 인원과 같아도 전원 당첨 — 추첨 요청 없이 반영, 남은 확보 없음")
    void paidEqualToWinnersAllWin() {
        UUID draw = closedDraw(2);
        UUID a = entry(draw, "PAID", null);
        UUID b = entry(draw, "PAID", null);

        assertThat(settlement.close(draw)).isTrue();

        assertThat(wonOrder(a)).isNotNull();
        assertThat(wonOrder(b)).isNotNull();
        assertThat(campaign(draw).get("draw_requested_at")).isNull();
        assertThat(stock()).containsEntry("stock_reserved", 0).containsEntry("stock_sold", 2);
    }

    /** 고르는 조회와 별개로, 회차를 잠근 뒤 다시 판정한다 — 고른 뒤 상태가 바뀌었어도(다른 인스턴스 · 결제 반영) 그때의 사실로 가른다. */
    @Test
    @DisplayName("잠근 뒤 다시 판정 — 결제 확인 중이 남았거나 · 마감 전이거나 · 이미 요청했으면 처리하지 않는다")
    void closeRejudgesUnderLock() {
        UUID authorizing = closedDraw(1);
        entry(authorizing, "PAID", null);
        entry(authorizing, "AUTHORIZING", "window_rejudge");
        UUID open = draw(Instant.now().minus(Duration.ofHours(1)), Instant.now().plus(Duration.ofHours(1)), 1);
        entry(open, "PAID", null);
        UUID requested = closedDraw(1);
        entry(requested, "PAID", null);
        entry(requested, "PAID", null);
        assertThat(settlement.close(requested)).isTrue();

        assertThat(settlement.close(authorizing)).isFalse();
        assertThat(settlement.close(open)).isFalse();
        assertThat(settlement.close(requested)).as("이미 요청함").isFalse();
        assertThat(campaign(authorizing)).containsEntry("settled_at", null).containsEntry("draw_requested_at", null);
        assertThat(campaign(open)).containsEntry("settled_at", null).containsEntry("draw_requested_at", null);
        assertThat(outboxCount(requested, "DRAW_REQUESTED")).isEqualTo(1);
    }

    @Test
    @DisplayName("결제 확인 중 응모가 남았거나 마감 전이면 아무것도 하지 않는다")
    void waitsForAuthorizingAndClose() {
        UUID waiting = closedDraw(1);
        entry(waiting, "PAID", null);
        entry(waiting, "AUTHORIZING", "window_wait_1");
        UUID open = draw(Instant.now().minus(Duration.ofHours(1)), Instant.now().plus(Duration.ofHours(1)), 1);
        entry(open, "PAID", null);

        closer.closeDue();

        assertThat(campaign(waiting)).containsEntry("settled_at", null).containsEntry("draw_requested_at", null);
        assertThat(campaign(open)).containsEntry("settled_at", null).containsEntry("draw_requested_at", null);
    }

    @Test
    @DisplayName("결제 완료가 당첨 인원보다 많으면 DRAW_REQUESTED 하나(결제 완료 수 · 당첨 인원) — 다시 돌아도 한 번, 주문 · 재고는 그대로")
    void morePaidThanWinnersRequestsDraw() {
        UUID draw = closedDraw(2);
        entry(draw, "PAID", null);
        entry(draw, "PAID", null);
        entry(draw, "PAID", null);

        closer.closeDue();
        closer.closeDue();

        assertThat(campaign(draw).get("draw_requested_at")).isNotNull();
        assertThat(campaign(draw).get("settled_at")).isNull();
        List<String> payloads = jdbcTemplate.queryForList("""
                SELECT payload FROM order_outbox_events WHERE aggregate_type = 'DRAW_CAMPAIGN' AND aggregate_id = ? AND event_type = 'DRAW_REQUESTED'""",
                String.class, (Object) bytes(draw));
        assertThat(payloads).hasSize(1);
        JsonNode payload = JSON.readTree(payloads.getFirst());
        assertThat(payload.get("paidEntries").asLong()).isEqualTo(3);
        assertThat(payload.get("winnerCount").asInt()).isEqualTo(2);
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder("paidEntries", "winnerCount");
        assertThat(orderCount(draw)).isZero();
        assertThat(stock()).containsEntry("stock_reserved", 2).containsEntry("stock_sold", 0);
    }

    // ── 결과 반영 ──

    @Test
    @DisplayName("추첨 결과를 받으면 당첨 응모마다 0원 주문 · 판매 확정, 같은 결과를 또 받아도 한 번 · 내 응모는 당첨/낙첨")
    void drawResultIsSettledOnce() throws Exception {
        UUID draw = closedDraw(2);
        UUID customerA = fixtures.customer();
        UUID a = entry(draw, customerA, "PAID", null);
        UUID b = entry(draw, "PAID", null);
        UUID customerC = fixtures.customer();
        UUID c = entry(draw, customerC, "PAID", null);
        closer.closeDue();
        mockMvc.perform(get("/api/v1/draws/{id}/entries/me", draw).with(TestAuth.customer(customerA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.result").doesNotExist());

        dispatcher.dispatch(result(draw, a, b));
        dispatcher.dispatch(result(draw, a, b));

        assertThat(wonOrder(a)).isNotNull();
        assertThat(wonOrder(b)).isNotNull();
        assertThat(wonOrder(c)).isNull();
        assertThat(orderCount(draw)).isEqualTo(2);
        assertThat(stock()).containsEntry("stock_reserved", 0).containsEntry("stock_sold", 2);
        mockMvc.perform(get("/api/v1/draws/{id}/entries/me", draw).with(TestAuth.customer(customerA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.result").value("WON"));
        mockMvc.perform(get("/api/v1/draws/{id}/entries/me", draw).with(TestAuth.customer(customerC)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.result").value("LOST"));
        mockMvc.perform(get("/api/v1/admin/draws/{id}/progress", draw).with(TestAuth.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paidEntries").value(3))
                .andExpect(jsonPath("$.data.authorizingEntries").value(0))
                .andExpect(jsonPath("$.data.winners").value(2))
                .andExpect(jsonPath("$.data.settledAt").exists());
    }

    @Test
    @DisplayName("계약에 맞지 않는 결과는 반영하지 않는다 — 요청 안 한 회차 · 당첨 수가 다름 · 같은 응모 두 번 · 그 회차의 결제 완료가 아님")
    void invalidResultsChangeNothing() {
        UUID notRequested = closedDraw(1);
        UUID lone = entry(notRequested, "PAID", null);
        entry(notRequested, "PAID", null);
        // 요청 전(주기 작업 전)에 온 결과
        assertThatThrownBy(() -> settlement.settle(notRequested, List.of(lone))).isInstanceOf(IllegalArgumentException.class);

        UUID draw = closedDraw(2);
        UUID a = entry(draw, "PAID", null);
        UUID b = entry(draw, "PAID", null);
        entry(draw, "PAID", null);
        UUID unpaid = entry(draw, "AWAITING_PAYMENT", null);
        closer.closeDue();

        assertThatThrownBy(() -> settlement.settle(draw, List.of(a))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settlement.settle(draw, List.of(a, a))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settlement.settle(draw, List.of(a, unpaid))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settlement.settle(draw, List.of(a, lone))).as("다른 회차의 응모").isInstanceOf(IllegalArgumentException.class);

        assertThat(campaign(draw).get("settled_at")).isNull();
        assertThat(orderCount(draw)).isZero();
        assertThat(stock()).containsEntry("stock_reserved", 3).containsEntry("stock_sold", 0);
        settlement.settle(draw, List.of(a, b));
        assertThat(orderCount(draw)).isEqualTo(2);
    }

    /**
     * 주기 작업이 겹치면(인스턴스 둘) 회차 행 잠금으로 한쪽만 반영한다. 결정적으로 본다: 앞선 처리가 회차를 잠그고 반영한 채 커밋하지 않고,
     * 늦은 처리가 그 행 잠금에서 기다리는 것을 확인한 뒤 커밋한다.
     */
    @Test
    @DisplayName("주기 작업이 겹쳐도 반영은 한 번 — 늦은 쪽은 반영 표식을 보고 지나간다")
    void overlappingClosersSettleOnce() throws Exception {
        UUID draw = closedDraw(2);
        entry(draw, "PAID", null);
        CountDownLatch closed = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            assertThat(settlement.close(draw)).isTrue();
            closed.countDown();
            await(commit);
        }));
        await(closed);
        CompletableFuture<Boolean> later = CompletableFuture.supplyAsync(() -> settlement.close(draw));
        waitForQuery("SELECT id FROM draw_campaigns%FOR UPDATE");
        commit.countDown();
        first.get(20, TimeUnit.SECONDS);

        assertThat(later.get(20, TimeUnit.SECONDS)).isFalse();
        assertThat(orderCount(draw)).isEqualTo(1);
        assertThat(stock()).containsEntry("stock_reserved", 0).containsEntry("stock_sold", 1);
    }

    @Test
    @DisplayName("주문 만들기 API 로는 드로우 주문을 만들 수 없다 — 400(source)")
    void drawOrdersCannotBePlacedByApi() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/orders")
                        .with(TestAuth.customer(fixtures.customer()))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"DRAW","shipTo":{"name":"홍길동","phone":"010-0000-0000","postalCode":"04524","line1":"서울시"}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("source"));
    }

    // ── 도우미 ──

    /** 마감된 회차. 당첨 인원만큼 재고를 확보해 둔다(회차 만들기와 같다). */
    private UUID closedDraw(int winners) {
        return draw(Instant.now().minus(Duration.ofDays(2)), Instant.now().minus(Duration.ofMinutes(1)), winners);
    }

    private UUID draw(Instant opens, Instant closes, int winners) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO draw_campaigns (id, idempotency_key, product_id, option_id, title, product_title_snapshot, option_title_snapshot,
                                            entry_fee, winner_count, opens_at, closes_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, '드로우', '증정품', '블랙', 100, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                bytes(id), id.toString(), bytes(product), bytes(option), winners, utc(opens), utc(closes));
        jdbcTemplate.update("""
                INSERT INTO option_inventories (option_id, stock_total, stock_reserved, stock_sold, updated_at)
                VALUES (?, 10, ?, 0, UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE stock_reserved = stock_reserved + ?""", bytes(option), winners, winners);
        return id;
    }

    private UUID entry(UUID draw, String status, String window) {
        return entry(draw, fixtures.customer(), status, window);
    }

    private UUID entry(UUID draw, UUID customer, String status, String window) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO draw_entries (id, campaign_id, customer_id, status, authorizing_provider_order_id, ship_to_name, ship_to_phone,
                                          ship_to_postal_code, ship_to_line1, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, '당첨자', '010-0000-0000', '04524', '서울시 중구', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""",
                bytes(id), bytes(draw), bytes(customer), status, window);
        return id;
    }

    private static String result(UUID draw, UUID... winners) {
        String ids = java.util.Arrays.stream(winners).map(w -> "\"" + w + "\"").collect(Collectors.joining(","));
        return """
                {"eventId":"%s","eventType":"DRAW_RESULT_READY","aggregateType":"DRAW_CAMPAIGN","aggregateId":"%s",
                 "occurredAt":"2026-10-11T00:00:00Z","payload":{"winnerEntryIds":[%s]}}""".formatted(UUID.randomUUID(), draw, ids);
    }

    private byte[] wonOrder(UUID entry) {
        return jdbcTemplate.queryForObject("SELECT won_order_id FROM draw_entries WHERE id = ?", byte[].class, (Object) bytes(entry));
    }

    private long orderCount(UUID draw) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM draw_entries WHERE campaign_id = ? AND won_order_id IS NOT NULL", Long.class,
                (Object) bytes(draw));
    }

    private Map<String, Object> campaign(UUID draw) {
        return jdbcTemplate.queryForMap("SELECT draw_requested_at, settled_at FROM draw_campaigns WHERE id = ?", (Object) bytes(draw));
    }

    private Map<String, Object> stock() {
        return jdbcTemplate.queryForMap("SELECT stock_reserved, stock_sold FROM option_inventories WHERE option_id = ?", (Object) bytes(option));
    }

    private long outboxCount(UUID draw, String type) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_outbox_events WHERE aggregate_id = ? AND event_type = ?", Long.class,
                bytes(draw), type);
    }

    private static String utc(Instant at) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS").withZone(ZoneOffset.UTC).format(at);
    }

    private void waitForQuery(String like) {
        Awaitility.await("그 문장이 기다리지 않았다: " + like).atMost(Duration.ofSeconds(10)).until(() -> {
            Long running = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE COMMAND = 'Query' AND INFO LIKE ?", Long.class, like);
            return running != null && running > 0;
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("기다리다 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
