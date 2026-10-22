package com.grandis.nova.order.order.api;

import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.PlacedOrders;
import com.grandis.nova.order.support.TestAuth;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@OrderIntegrationTest
@AutoConfigureMockMvc
class OrderQueryApiTest {

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
    void listsOnlyMyOrdersNewestFirst() throws Exception {
        Order older = orders.place(customerId);
        Order newer = orders.place(customerId);
        orders.place(fixtures.customer());

        mockMvc.perform(get("/api/v1/orders").with(me()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].orderId").value(newer.id().toString()))
                .andExpect(jsonPath("$.data.items[1].orderId").value(older.id().toString()))
                .andExpect(jsonPath("$.data.items[0].items[0].productTitle").value(OrderFixtures.PRODUCT_TITLE))
                .andExpect(jsonPath("$.data.items[0].items[0].optionTitle").value(OrderFixtures.OPTION_TITLE))
                .andExpect(jsonPath("$.data.nextCursor").isEmpty());
    }

    @Test
    void emptyListHasNoCursor() throws Exception {
        mockMvc.perform(get("/api/v1/orders").with(me()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.nextCursor").isEmpty());
    }

    @Test
    void cursorContinuesToLastPage() throws Exception {
        List<Order> placed = List.of(orders.place(customerId), orders.place(customerId), orders.place(customerId));

        String firstPage = mockMvc.perform(get("/api/v1/orders").param("size", "2").with(me()))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].orderId").value(placed.get(2).id().toString()))
                .andExpect(jsonPath("$.data.items[1].orderId").value(placed.get(1).id().toString()))
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(firstPage, "$.data.nextCursor");

        mockMvc.perform(get("/api/v1/orders").param("size", "2").param("cursor", cursor).with(me()))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].orderId").value(placed.getFirst().id().toString()))
                .andExpect(jsonPath("$.data.nextCursor").isEmpty());
    }

    /* 같은 시각의 주문이 경계에 걸려도 응답의 문자열 커서만으로 끝까지 한 번씩 읽는다. */
    @Test
    void stringCursorPagesThroughSameCreatedAt() throws Exception {
        List<Order> placed = List.of(orders.place(customerId), orders.place(customerId), orders.place(customerId));
        placed.forEach(order -> jdbcTemplate.update("UPDATE orders SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(java.time.Instant.parse("2020-01-01T00:00:00.5Z")), bytes(order.id())));

        List<String> seen = new java.util.ArrayList<>();
        String cursor = null;
        do {
            var request = get("/api/v1/orders").param("size", "1").with(me());
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            String body = mockMvc.perform(request).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            List<String> ids = JsonPath.read(body, "$.data.items[*].orderId");
            seen.addAll(ids);
            cursor = JsonPath.read(body, "$.data.nextCursor");
        } while (cursor != null && seen.size() <= placed.size());

        // 같은 시각이면 id 내림차순이다. id 는 만든 순서를 보장하지 않으므로 기대 순서도 id 로 정한다
        assertThat(seen).containsExactlyElementsOf(placed.stream()
                .sorted(java.util.Comparator.comparing(Order::id).reversed())
                .map(order -> order.id().toString()).toList());
    }

    /* 상한 바로 아래의 나노초 커서. 바인딩 때 반올림돼 DATETIME 범위를 넘으면 500 이 된다. */
    @Test
    void subMicrosecondCursorNearUpperBoundIsNotServerError() throws Exception {
        orders.place(customerId);

        mockMvc.perform(get("/api/v1/orders").param("cursor", encode("9999-12-31T23:59:59.9999995Z|" + UUID.randomUUID())).with(me()))
                .andExpect(status().isOk());
    }

    @Test
    void malformedCursorOrSizeIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("size", "0").with(me()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("size"));
        mockMvc.perform(get("/api/v1/orders").param("size", "101").with(me()))
                .andExpect(status().isBadRequest());
        for (String brokenCursor : List.of("!!not-base64!!", encode("어제|3"), encode("2026-10-01T00:00:00Z"),
                encode("+10000-01-01T00:00:00Z|1"), encode("+1000000000-12-31T23:59:59Z|1"))) {
            mockMvc.perform(get("/api/v1/orders").param("cursor", brokenCursor).with(me()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
    }

    /* 상태를 여러 번 주면 그중 하나인 주문만, 최신순으로. counts 는 조건과 상관없이 내 주문 전부다. */
    @Test
    void filtersByStatusesAndCountsAllMyOrders() throws Exception {
        Order shipped = orders.place(customerId);
        Order delivered = orders.place(customerId);
        Order canceled = orders.place(customerId);
        orders.place(customerId);
        setStatus(shipped, "SHIPPED");
        setStatus(delivered, "DELIVERED");
        setStatus(canceled, "CANCELED");
        setStatus(orders.place(fixtures.customer()), "DELIVERED");

        mockMvc.perform(get("/api/v1/orders").param("status", "SHIPPED", "DELIVERED").with(me()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].orderId", contains(delivered.id().toString(), shipped.id().toString())))
                .andExpect(jsonPath("$.data.counts.AWAITING_PAYMENT").value(1))
                .andExpect(jsonPath("$.data.counts.SHIPPED").value(1))
                .andExpect(jsonPath("$.data.counts.DELIVERED").value(1))
                .andExpect(jsonPath("$.data.counts.CANCELED").value(1))
                .andExpect(jsonPath("$.data.counts.READY_TO_SHIP").value(0))
                .andExpect(jsonPath("$.data.counts.length()").value(9));
    }

    /* 커서는 같은 조건 안에서 이어 읽는다 — 조건 밖의 주문이 사이에 있어도 건너뛴다. */
    @Test
    void cursorContinuesWithinTheFilter() throws Exception {
        Order oldest = orders.place(customerId);
        orders.place(customerId);
        Order middle = orders.place(customerId);
        orders.place(customerId);
        setStatus(oldest, "DELIVERED");
        setStatus(middle, "DELIVERED");

        String firstPage = mockMvc.perform(get("/api/v1/orders").param("status", "DELIVERED").param("size", "1").with(me()))
                .andExpect(jsonPath("$.data.items[*].orderId", contains(middle.id().toString())))
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(firstPage, "$.data.nextCursor");

        mockMvc.perform(get("/api/v1/orders").param("status", "DELIVERED").param("size", "1").param("cursor", cursor).with(me()))
                .andExpect(jsonPath("$.data.items[*].orderId", contains(oldest.id().toString())))
                .andExpect(jsonPath("$.data.nextCursor").isEmpty());
    }

    /* from 은 그 시각 이후(같은 시각 포함) 주문만. counts 는 기간과 상관없다. */
    @Test
    void fromKeepsOrdersAtOrAfterTheInstant() throws Exception {
        Order old = orders.place(customerId);
        Order atBoundary = orders.place(customerId);
        Order recent = orders.place(customerId);
        setCreatedAt(old, "2026-01-31T23:59:59.999999Z");
        setCreatedAt(atBoundary, "2026-02-01T00:00:00Z");
        setCreatedAt(recent, "2026-03-01T00:00:00Z");

        mockMvc.perform(get("/api/v1/orders").param("from", "2026-02-01T00:00:00Z").with(me()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].orderId", contains(recent.id().toString(), atBoundary.id().toString())))
                .andExpect(jsonPath("$.data.counts.AWAITING_PAYMENT").value(3));
    }

    @Test
    void unknownStatusOrOutOfRangeFromIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("status", "SHIPPING").with(me()))
                .andExpect(status().isBadRequest());
        // 빈 원소가 섞이면 400. status= 하나뿐이면 보내지 않은 것과 같다(조건 없음)
        mockMvc.perform(get("/api/v1/orders").param("status", "").with(me())).andExpect(status().isOk());
        for (String[] blank : List.of(new String[]{"SHIPPED", ""}, new String[]{"SHIPPED,"})) {
            mockMvc.perform(get("/api/v1/orders").param("status", blank).with(me()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.details.violations[0].field").value("status"));
        }
        mockMvc.perform(get("/api/v1/orders").param("from", "+10000-01-01T00:00:00Z").with(me()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("from"));
    }

    @Test
    void detailHasItemsAndHistoryButNoInternalFields() throws Exception {
        Order order = orders.place(customerId);
        orders.cancel(order.id(), "USER_REQUEST");

        mockMvc.perform(get("/api/v1/orders/" + order.id().toString()).with(me()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(order.id().toString()))
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.source").value("PREORDER"))
                .andExpect(jsonPath("$.data.totalAmount").value(OrderFixtures.UNIT_PRICE.intValue()))
                .andExpect(jsonPath("$.data.shipTo.name").value("홍길동"))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].quantity").value(1))
                .andExpect(jsonPath("$.data.events", hasSize(2)))
                .andExpect(jsonPath("$.data.events[0].eventSequence").value(1))
                .andExpect(jsonPath("$.data.events[0].fromStatus").isEmpty())
                .andExpect(jsonPath("$.data.events[0].toStatus").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.data.events[0].actor").value("USER"))
                .andExpect(jsonPath("$.data.events[1].toStatus").value("CANCELED"))
                .andExpect(jsonPath("$.data.events[1].actor").value("SYSTEM"))
                .andExpect(jsonPath("$.data.events[1].reason").value("USER_REQUEST"))
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$.data.paymentDueAt").doesNotExist())
                .andExpect(jsonPath("$.data.customerId").doesNotExist());
    }

    /** 주문상품마다 order_items.id 가 orderItemId 로 나간다 — 목록 · 상세 모두. 리뷰 작성 등 주문상품 하나를 가리킬 때 쓴다. */
    @Test
    void itemsCarryOrderItemId() throws Exception {
        Order order = orders.place(customerId);
        String orderItemId = UuidBinary.fromBytes(jdbcTemplate.queryForObject("SELECT id FROM order_items WHERE order_id = ?", byte[].class,
                (Object) OrderFixtures.bytes(order.id()))).toString();

        mockMvc.perform(get("/api/v1/orders/" + order.id()).with(me()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].orderItemId").value(orderItemId));
        mockMvc.perform(get("/api/v1/orders").with(me()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.orderId == '%s')].items[*].orderItemId".formatted(order.id()), contains(orderItemId)));
    }

    @Test
    void someoneElsesOrderIsNotFound() throws Exception {
        Order others = orders.place(fixtures.customer());

        mockMvc.perform(get("/api/v1/orders/" + others.id().toString()).with(me()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void unknownOrMalformedOrderIdIsNotFound() throws Exception {
        for (String orderId : List.of(UUID.randomUUID().toString(), "not-a-token")) {
            mockMvc.perform(get("/api/v1/orders/" + orderId).with(me()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
        }
    }

    /* 관리자는 회원 id 가 없어 "내 주문" 이 없다. 관리자 경로를 쓴다. */
    @Test
    void adminTokenIsForbiddenOnUserApi() throws Exception {
        Order order = orders.place(customerId);

        mockMvc.perform(get("/api/v1/orders").with(TestAuth.admin()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/orders/" + order.id().toString()).with(TestAuth.admin()))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
    }

    private void setStatus(Order order, String status) {
        jdbcTemplate.update("UPDATE orders SET status = ? WHERE id = ?", status, bytes(order.id()));
    }

    /** DB 는 UTC 벽시계 시각을 담는다 — Timestamp 로 넣으면 JVM 시간대만큼 어긋난다. */
    private void setCreatedAt(Order order, String createdAt) {
        jdbcTemplate.update("UPDATE orders SET created_at = ? WHERE id = ?",
                java.time.LocalDateTime.ofInstant(java.time.Instant.parse(createdAt), java.time.ZoneOffset.UTC), bytes(order.id()));
    }

    private RequestPostProcessor me() {
        return TestAuth.customer(customerId);
    }

    private static String encode(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
