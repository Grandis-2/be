package com.grandis.nova.order.order.api;

import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.PlacedOrders;
import com.grandis.nova.order.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** catalog 가 리뷰 작성 때 부르는 주문상품 조회(contracts/order-internal.md). 회원 본인 것만, 사실만 돌려준다. */
@OrderIntegrationTest
@AutoConfigureMockMvc
class OrderItemLookupApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    OrderLedger ledger;

    @Autowired
    TransactionTemplate transactionTemplate;

    OrderFixtures fixtures;
    PlacedOrders orders;
    Long customerId;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        orders = new PlacedOrders(ledger, transactionTemplate, fixtures);
        customerId = fixtures.customer();
    }

    /* 판정 없이 사실만 — 주문 내부 id · 상품 · 옵션 · 주문 당시 옵션명 · 주문 상태 · 출처 */
    @Test
    void ownItemReturnsFacts() throws Exception {
        Order order = orders.place(customerId);
        fixtures.forceStatus(order.id(), "DELIVERED");
        Long itemId = itemOf(order);
        var row = jdbcTemplate.queryForMap(
                "SELECT product_id, option_id, option_title_snapshot FROM order_items WHERE id = ?", itemId);

        mockMvc.perform(item(itemId).with(me()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderItemId").value(itemId))
                .andExpect(jsonPath("$.data.orderId").value(order.id()))
                .andExpect(jsonPath("$.data.productId").value(((Number) row.get("product_id")).longValue()))
                .andExpect(jsonPath("$.data.optionId").value(((Number) row.get("option_id")).longValue()))
                .andExpect(jsonPath("$.data.optionTitle").value(row.get("option_title_snapshot")))
                .andExpect(jsonPath("$.data.orderStatus").value("DELIVERED"))
                .andExpect(jsonPath("$.data.orderSource").value("PREORDER"));
    }

    /* 배송 전이어도 그대로 답한다 — 리뷰를 막는 판정은 catalog 가 한다. */
    @Test
    void statusIsReportedAsIs() throws Exception {
        Order order = orders.place(customerId);

        mockMvc.perform(item(itemOf(order)).with(me()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderStatus").value("AWAITING_PAYMENT"));
    }

    /* 남의 주문상품은 없는 것과 같다 — 존재를 드러내지 않는다. */
    @Test
    void someoneElsesItemIsNotFound() throws Exception {
        Order others = orders.place(fixtures.customer());

        mockMvc.perform(item(itemOf(others)).with(me()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_ITEM_NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void missingItemIsNotFound() throws Exception {
        mockMvc.perform(item(987654321L).with(me()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_ITEM_NOT_FOUND"));
    }

    /* 회원만 — 관리자 토큰은 회원 id 가 없어 403. */
    @Test
    void adminIsForbidden() throws Exception {
        Order order = orders.place(customerId);

        mockMvc.perform(item(itemOf(order)).with(TestAuth.admin()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        Order order = orders.place(customerId);

        mockMvc.perform(item(itemOf(order)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void malformedIdIsBadRequest() throws Exception {
        mockMvc.perform(get("/internal/order-items/abc").with(me()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    private Long itemOf(Order order) {
        return jdbcTemplate.queryForObject("SELECT id FROM order_items WHERE order_id = ?", Long.class, order.id());
    }

    private static MockHttpServletRequestBuilder item(Long orderItemId) {
        return get("/internal/order-items/{orderItemId}", orderItemId);
    }

    private RequestPostProcessor me() {
        return TestAuth.customer(customerId);
    }
}
