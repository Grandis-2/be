package com.grandis.nova.order.stock.api;

import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.command.PlaceOrderCommand;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderTrigger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.stock.StockLedger;
import com.grandis.nova.order.stock.domain.model.StockBalance;
import com.grandis.nova.order.stock.domain.model.StockReconcileRun;
import com.grandis.nova.order.stock.domain.repository.StockReconcileRuns;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderFixtures.StockProduct;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 재고 보정(주문 기준으로 다시 맞추기) · 미리보기 · 마지막 대조 결과. 요청마다 커밋하므로 결과를 DB 에서 본다.
 * 어긋남은 원장으로 만든 주문 위에 장부를 SQL 로 고치거나, 재고를 옮기지 않는 원장 전이(결제 반영만)로 만든다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
class StockCorrectionTest {

    static final BigDecimal PRICE = new BigDecimal("1250000");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired OrderLedger orderLedger;
    @Autowired StockLedger stockLedger;
    @Autowired StockReconcileRuns runs;

    OrderFixtures fixtures;
    TransactionTemplate tx;
    StockProduct product;
    UUID option;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        tx = new TransactionTemplate(transactionManager);
        product = fixtures.inStockProduct(1);
        option = product.optionIds().getFirst();
    }

    @Test
    @DisplayName("미리보기는 장부와 주문 기준을 보이고, 보정은 확보 · 판매를 주문 기준으로 덮고 기록을 남긴다 — 다시 하면 바뀌지 않고 기록도 없다")
    void previewThenCorrectToOrderBasedCounts() throws Exception {
        fixtures.stock(option, 10, 0, 0);
        place(option, 2, true);
        Order paidWithoutSale = place(option, 3, true);
        // 결제 반영만 하고 판매 확정은 하지 않는다 — 결제 반영이 판매를 놓친 어긋남
        tx.executeWithoutResult(status -> {
            orderLedger.requestPayment(paidWithoutSale.id(), UUID.randomUUID().toString(), EventCause.user());
            orderLedger.settlePayment(paidWithoutSale.id(), OrderTrigger.PAYMENT_APPROVED, null, null, EventCause.system("PAYMENT_APPROVED"));
        });

        preview(product.productId(), option).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.optionId").value(option.toString()))
                .andExpect(jsonPath("$.data.registered").value(true))
                .andExpect(jsonPath("$.data.stockTotal").value(10))
                .andExpect(jsonPath("$.data.stockReserved").value(5))
                .andExpect(jsonPath("$.data.stockSold").value(0))
                .andExpect(jsonPath("$.data.expectedReserved").value(2))
                .andExpect(jsonPath("$.data.expectedSold").value(3))
                .andExpect(jsonPath("$.data.matches").value(false));

        correct(product.productId(), option, "{\"reason\":\"결제 반영의 판매 확정 누락\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.stockTotal").value(10))
                .andExpect(jsonPath("$.data.before.stockReserved").value(5))
                .andExpect(jsonPath("$.data.before.stockSold").value(0))
                .andExpect(jsonPath("$.data.after.stockReserved").value(2))
                .andExpect(jsonPath("$.data.after.stockSold").value(3));

        assertThat(stockOf(option)).containsExactly(10, 2, 3);
        List<Map<String, Object>> adjustments = adjustments(option);
        assertThat(adjustments).hasSize(1);
        assertThat(adjustments.getFirst()).containsEntry("before_reserved", 5).containsEntry("before_sold", 0)
                .containsEntry("after_reserved", 2).containsEntry("after_sold", 3).containsEntry("stock_total", 10)
                .containsEntry("reason", "결제 반영의 판매 확정 누락").containsEntry("requested_by", "admin");

        correct(product.productId(), option, "{\"reason\":\"다시\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(false));
        assertThat(adjustments(option)).as("바뀌지 않은 보정은 기록하지 않는다").hasSize(1);
        preview(product.productId(), option).andExpect(jsonPath("$.data.matches").value(true));
    }

    @Test
    @DisplayName("재고 행이 없는데 주문 기준이 있으면 409 — 총량을 먼저 넣어야 한다, 행을 만들지 않는다")
    void missingRowWithOrdersIsConflict() throws Exception {
        place(option, 2, false);

        correct(product.productId(), option, "{\"reason\":\"보정\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STOCK_TOTAL_BELOW_EXPECTED"))
                .andExpect(jsonPath("$.error.details.optionId").value(option.toString()))
                .andExpect(jsonPath("$.error.details.registered").value(false))
                .andExpect(jsonPath("$.error.details.total").value(0))
                .andExpect(jsonPath("$.error.details.expectedReserved").value(2))
                .andExpect(jsonPath("$.error.details.expectedSold").value(0));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM option_inventories WHERE option_id = ?", Long.class,
                (Object) bytes(option))).isZero();
        assertThat(adjustments(option)).isEmpty();
    }

    @Test
    @DisplayName("주문 기준 확보 + 판매가 총량을 넘으면 409 와 수치 — 장부는 그대로다")
    void expectationAboveTotalIsConflict() throws Exception {
        fixtures.stock(option, 3, 0, 0);
        place(option, 3, true);
        place(option, 2, false);

        correct(product.productId(), option, "{\"reason\":\"보정\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STOCK_TOTAL_BELOW_EXPECTED"))
                .andExpect(jsonPath("$.error.details.registered").value(true))
                .andExpect(jsonPath("$.error.details.total").value(3))
                .andExpect(jsonPath("$.error.details.expectedReserved").value(5));

        assertThat(stockOf(option)).containsExactly(3, 3, 0);
        assertThat(adjustments(option)).isEmpty();
    }

    @Test
    @DisplayName("재고 행도 주문도 없으면 맞출 것이 없다 — 200, changed=false, 행을 만들지 않는다")
    void nothingToCorrectWithoutRowOrOrders() throws Exception {
        correct(product.productId(), option, "{\"reason\":\"보정\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.registered").value(false))
                .andExpect(jsonPath("$.data.changed").value(false));

        preview(product.productId(), option).andExpect(jsonPath("$.data.registered").value(false))
                .andExpect(jsonPath("$.data.matches").value(true));
    }

    @Test
    @DisplayName("사유가 없거나 비었거나 500자를 넘으면 400 이고 장부를 바꾸지 않는다 — 500자는 코드 포인트로 센다(이모지 500자는 받는다)")
    void reasonIsRequiredAndBounded() throws Exception {
        fixtures.stock(option, 10, 4, 0);

        correct(product.productId(), option, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        correct(product.productId(), option, "{\"reason\":\"  \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        correct(product.productId(), option, "{\"reason\":\"%s\"}".formatted("가".repeat(501))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        assertThat(stockOf(option)).containsExactly(10, 4, 0);

        correct(product.productId(), option, "{\"reason\":\"%s\"}".formatted("😀".repeat(500))).andExpect(status().isOk());
        assertThat(stockOf(option)).containsExactly(10, 0, 0);
    }

    @Test
    @DisplayName("다른 상품의 옵션은 400(optionId), 사전예약 상품은 409 STOCK_NOT_TRACKED, 없는 상품은 404 — 미리보기 · 보정 모두")
    void targetMustBeAnOptionOfAStockedProduct() throws Exception {
        UUID foreign = fixtures.inStockProduct(1).optionIds().getFirst();
        OrderFixtures.PreorderProduct preorder = fixtures.preorderProduct();

        preview(product.productId(), foreign).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("optionId"));
        correct(product.productId(), foreign, "{\"reason\":\"보정\"}").andExpect(status().isBadRequest());
        preview(preorder.productId(), preorder.optionId()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STOCK_NOT_TRACKED"));
        correct(UUID.randomUUID(), option, "{\"reason\":\"보정\"}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    @DisplayName("마지막 대조 결과는 어긋난 옵션과 상품을 싣고, 대조 뒤 보정한 옵션에는 보정 시각을 붙인다")
    void latestRunListsDriftsAndMarksCorrectedOnes() throws Exception {
        fixtures.stock(option, 10, 4, 1);
        // 마지막 대조는 끝난 시각으로 고른다 — 다른 시험이 방금 남긴 기록이 이 기록보다 늦을 수 있어 비우고 시작한다
        jdbcTemplate.update("DELETE FROM stock_reconcile_runs");
        Instant finished = Instant.now().minusSeconds(1);
        runs.save(StockReconcileRun.of(finished.minusMillis(200), finished, 1,
                List.of(new StockBalance(option, product.productId(), true, 10, 4, 1, 0, 0))));

        latest().andExpect(status().isOk())
                .andExpect(jsonPath("$.data.driftOptions").value(1))
                .andExpect(jsonPath("$.data.truncated").value(false))
                .andExpect(jsonPath("$.data.options[0].optionId").value(option.toString()))
                .andExpect(jsonPath("$.data.options[0].productId").value(product.productId().toString()))
                .andExpect(jsonPath("$.data.options[0].stockReserved").value(4))
                .andExpect(jsonPath("$.data.options[0].expectedReserved").value(0))
                .andExpect(jsonPath("$.data.options[0].correctedAt").value(nullValue()));

        correct(product.productId(), option, "{\"reason\":\"대조 결과로 보정\"}").andExpect(status().isOk());

        latest().andExpect(jsonPath("$.data.options[0].correctedAt").isNotEmpty());
        mockMvc.perform(get("/api/v1/admin/stock/reconcile-runs/latest").with(TestAuth.customer(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("기록은 어긋난 옵션을 앞 1000개까지만 싣고 잘랐다고 적는다 — 수는 전부다")
    void runDetailIsCapped() {
        List<StockBalance> drifts = java.util.stream.IntStream.range(0, StockReconcileRun.DETAIL_LIMIT + 1)
                .mapToObj(i -> new StockBalance(UUID.randomUUID(), null, true, 1, 1, 0, 0, 0)).toList();

        StockReconcileRun run = StockReconcileRun.of(Instant.now(), Instant.now(), drifts.size(), drifts);

        assertThat(run.driftOptions()).isEqualTo(StockReconcileRun.DETAIL_LIMIT + 1);
        assertThat(run.drifts()).hasSize(StockReconcileRun.DETAIL_LIMIT);
        assertThat(run.truncated()).isTrue();
    }

    /** 장바구니 주문 하나를 원장으로 만든다. reserve=false 면 확보 없이 — 장부 밖에서 생긴 주문(수동 SQL 등)을 흉내 낸다. */
    private Order place(UUID optionId, int quantity, boolean reserve) {
        PlaceOrderCommand command = new PlaceOrderCommand(fixtures.customer(), OrderSource.CART, null, null, null, OrderFixtures.ADDRESS,
                List.of(new PlaceOrderCommand.Line(product.productId(), optionId, quantity, PRICE, 0, BigDecimal.ZERO, "아이폰 17", null, "블랙")));
        return tx.execute(status -> {
            if (reserve) {
                stockLedger.reserve(Map.of(optionId, quantity));
            }
            return orderLedger.place(command.toDraft(), EventCause.user());
        });
    }

    private ResultActions preview(UUID productId, UUID optionId) throws Exception {
        return mockMvc.perform(get("/api/v1/admin/products/{productId}/stock/options/{optionId}/reconcile", productId, optionId)
                .with(TestAuth.admin()));
    }

    private ResultActions correct(UUID productId, UUID optionId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/products/{productId}/stock/options/{optionId}/reconcile", productId, optionId)
                .with(TestAuth.admin()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions latest() throws Exception {
        return mockMvc.perform(get("/api/v1/admin/stock/reconcile-runs/latest").with(TestAuth.admin()));
    }

    /** [총량, 확보, 판매] */
    private List<Integer> stockOf(UUID optionId) {
        return jdbcTemplate.queryForObject("SELECT stock_total, stock_reserved, stock_sold FROM option_inventories WHERE option_id = ?",
                (rs, n) -> List.of(rs.getInt(1), rs.getInt(2), rs.getInt(3)), (Object) bytes(optionId));
    }

    private List<Map<String, Object>> adjustments(UUID optionId) {
        return jdbcTemplate.queryForList("""
                SELECT before_reserved, before_sold, after_reserved, after_sold, stock_total, reason, requested_by
                  FROM stock_adjustments WHERE option_id = ? ORDER BY created_at""", (Object) bytes(optionId));
    }
}
