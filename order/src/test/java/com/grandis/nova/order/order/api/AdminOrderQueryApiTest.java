package com.grandis.nova.order.order.api;

import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.PlacedOrders;
import com.grandis.nova.order.support.TestAuth;
import com.grandis.nova.order.web.PageSizes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 주문 조회. 컨테이너를 다른 테스트와 함께 쓰고 행을 지우지 않으므로 "전체가 무엇인가" 를 가정하지 않는다 —
 * 대상 주문이 들어 있는지, 받은 줄이 모두 조건 · 정렬을 지키는지로 본다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
class AdminOrderQueryApiTest {

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
    UUID customerId;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        orders = new PlacedOrders(ledger, transactionTemplate, fixtures);
        customerId = fixtures.customer();
    }

    @Test
    void listsOrdersOfAllCustomersNewestFirstWithTotal() throws Exception {
        Order others = orders.place(fixtures.customer());
        Order mine = orders.place(customerId);

        String body = mockMvc.perform(get("/api/v1/admin/orders").param("size", "" + PageSizes.MAX).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(PageSizes.MAX))
                .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(2)))
                .andReturn().getResponse().getContentAsString();

        List<String> ids = JsonPath.read(body, "$.data.items[*].orderId");
        assertThat(ids).contains(others.orderToken().value(), mine.orderToken().value());
        assertThat(ids.indexOf(mine.orderToken().value())).isLessThan(ids.indexOf(others.orderToken().value()));
        List<String> createdAts = JsonPath.read(body, "$.data.items[*].createdAt");
        assertThat(createdAts.stream().map(Instant::parse).toList())
                .isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(JsonPath.<List<String>>read(body, "$.data.items[?(@.orderId == '%s')].customerId"
                .formatted(mine.orderToken().value())))
                .containsExactly(customerId.toString());
        assertThat(JsonPath.<List<String>>read(body, "$.data.items[?(@.orderId == '%s')].items[0].productTitle"
                .formatted(mine.orderToken().value()))).containsExactly(OrderFixtures.PRODUCT_TITLE);
    }

    @Test
    void filtersByStatusAndSource() throws Exception {
        Order canceled = orders.place(customerId);
        orders.cancel(canceled.id(), "EXPIRY");
        Order awaiting = orders.place(customerId);

        String byStatus = mockMvc.perform(get("/api/v1/admin/orders").param("status", "CANCELED")
                        .param("size", "" + PageSizes.MAX).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].status", everyItem(is("CANCELED"))))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(byStatus, "$.data.items[*].orderId"))
                .contains(canceled.orderToken().value())
                .doesNotContain(awaiting.orderToken().value());

        String bySource = mockMvc.perform(get("/api/v1/admin/orders").param("status", "AWAITING_PAYMENT")
                        .param("source", "PREORDER").param("size", "" + PageSizes.MAX).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].source", everyItem(is("PREORDER"))))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(bySource, "$.data.items[*].orderId"))
                .contains(awaiting.orderToken().value());

        String otherSource = mockMvc.perform(get("/api/v1/admin/orders").param("source", "BUY_NOW")
                        .param("size", "" + PageSizes.MAX).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].source", everyItem(is("BUY_NOW"))))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(otherSource, "$.data.items[*].orderId"))
                .doesNotContain(canceled.orderToken().value(), awaiting.orderToken().value());
    }

    @Test
    void malformedFilterOrPagingIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/admin/orders").param("page", "-1").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("page"));
        mockMvc.perform(get("/api/v1/admin/orders").param("size", "0").with(admin()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/admin/orders").param("status", "NOPE").with(admin()))
                .andExpect(status().isBadRequest());
        // 건너뛸 행 수가 int 를 넘는 페이지 — 그대로 넘기면 Spring Data 가 거부해 500 이 된다.
        mockMvc.perform(get("/api/v1/admin/orders").param("page", "999999999").param("size", "20").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("page"));
    }

    @Test
    void detailAddsCustomerAndInternalNote() throws Exception {
        Order order = orders.place(customerId);
        // 관리자 메모 수정 API 는 이번 범위가 아니다. 칸이 실리는지만 본다.
        jdbcTemplate.update("UPDATE orders SET internal_note = ? WHERE id = ?", "연락 요망", bytes(order.id()));

        mockMvc.perform(get("/api/v1/admin/orders/" + order.orderToken().value()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(order.orderToken().value()))
                .andExpect(jsonPath("$.data.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.data.internalNote").value("연락 요망"))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.events", hasSize(1)))
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$.data.paymentDueAt").doesNotExist());
    }

    @Test
    void unknownOrderIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/admin/orders/" + UUID.randomUUID()).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void userTokenIsForbidden() throws Exception {
        Order order = orders.place(customerId);

        mockMvc.perform(get("/api/v1/admin/orders").with(TestAuth.customer(customerId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/orders/" + order.orderToken().value())
                        .with(TestAuth.customer(customerId)))
                .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor admin() {
        return TestAuth.admin();
    }
}
