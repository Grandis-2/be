package com.grandis.nova.order.order.api;

import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.command.PlaceOrderCommand;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.vo.EventCause;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
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
        assertThat(ids).contains(others.id().toString(), mine.id().toString());
        assertThat(ids.indexOf(mine.id().toString())).isLessThan(ids.indexOf(others.id().toString()));
        List<String> createdAts = JsonPath.read(body, "$.data.items[*].createdAt");
        assertThat(createdAts.stream().map(Instant::parse).toList())
                .isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(JsonPath.<List<String>>read(body, "$.data.items[?(@.orderId == '%s')].customerId"
                .formatted(mine.id().toString())))
                .containsExactly(customerId.toString());
        assertThat(JsonPath.<List<String>>read(body, "$.data.items[?(@.orderId == '%s')].items[0].productTitle"
                .formatted(mine.id().toString()))).containsExactly(OrderFixtures.PRODUCT_TITLE);
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
                .contains(canceled.id().toString())
                .doesNotContain(awaiting.id().toString());

        String bySource = mockMvc.perform(get("/api/v1/admin/orders").param("status", "AWAITING_PAYMENT")
                        .param("source", "PREORDER").param("size", "" + PageSizes.MAX).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].source", everyItem(is("PREORDER"))))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(bySource, "$.data.items[*].orderId"))
                .contains(awaiting.id().toString());

        String otherSource = mockMvc.perform(get("/api/v1/admin/orders").param("source", "BUY_NOW")
                        .param("size", "" + PageSizes.MAX).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].source", everyItem(is("BUY_NOW"))))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(otherSource, "$.data.items[*].orderId"))
                .doesNotContain(canceled.id().toString(), awaiting.id().toString());
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
    void detailAddsCustomer() throws Exception {
        Order order = orders.place(customerId);

        mockMvc.perform(get("/api/v1/admin/orders/" + order.id().toString()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(order.id().toString()))
                .andExpect(jsonPath("$.data.customerId").value(customerId.toString()))
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
    void filtersByOrderNumberCustomerAndPreorder() throws Exception {
        Order first = orders.place(customerId);
        Order second = orders.place(customerId);
        Order others = orders.place(fixtures.customer());
        String hex = first.id().toString().replace("-", "");

        assertListed(list("q", first.id().toString()), first);
        assertListed(list("q", hex.toUpperCase()), first);
        // 화면의 주문번호(앞 8자리) — 같은 65초 안에 만든 주문은 앞 8자리가 같아 함께 나온다
        assertThat(ids(list("q", hex.substring(0, 8)))).contains(first.id().toString())
                .allSatisfy(id -> assertThat(id).startsWith(hex.substring(0, 8)));
        assertThat(ids(list("q", hex.substring(0, 13)))).contains(first.id().toString())
                .allSatisfy(id -> assertThat(id.replace("-", "")).startsWith(hex.substring(0, 13)));
        assertListed(list("customerId", customerId.toString()), second, first);
        assertListed(list("customerId", customerId.toString(), "q", others.id().toString()));
        assertListed(list("preorderId", OrderFixtures.preorderToken(first.preorderId())), first);
        // 대문자로 줘도 같은 예약이다 — 저장된 공개 UUID 는 소문자다
        assertListed(list("preorderId", OrderFixtures.preorderToken(first.preorderId()).toUpperCase()), first);
        assertListed(list("preorderId", UUID.randomUUID().toString()));
    }

    @Test
    void shipToNameMatchesWholeName() throws Exception {
        String name = "받" + UUID.randomUUID().toString().substring(0, 8);
        Order exact = placeCart(customerId, name, fixtures.inStockProduct(1));
        Order longer = placeCart(customerId, name + "a", fixtures.inStockProduct(1));

        assertListed(list("shipToName", name), exact);
        assertListed(list("shipToName", " " + name + "a "), longer);
        assertListed(list("shipToName", name.substring(0, 5)));
        // 콜레이션(_ai_ci)을 따라 영문 대소문자는 가리지 않는다
        assertListed(list("shipToName", name.toUpperCase()), exact);
        assertListed(list("shipToName", name, "source", "PREORDER"));
        assertThat(ids(list("shipToName", " ", "customerId", customerId.toString())))
                .contains(exact.id().toString(), longer.id().toString());
    }

    @Test
    void shipToPhoneLast4MatchesDigitsWhateverTheFormat() throws Exception {
        String name = "전" + UUID.randomUUID().toString().substring(0, 8);
        OrderFixtures.StockProduct product = fixtures.inStockProduct(1);
        Order dashed = place(customerId, name, "010-1234-5678", line(product, 0));
        Order plain = place(customerId, name, "01098765678", line(product, 0));
        Order other = place(customerId, name, "010-5678-1234", line(product, 0));

        assertListed(list("shipToName", name, "shipToPhoneLast4", "5678"), plain, dashed);
        assertListed(list("shipToName", name, "shipToPhoneLast4", "1234"), other);
        assertListed(list("shipToName", name, "shipToPhoneLast4", "0000"));
    }

    @Test
    void productAndOptionMatchTheSameLine() throws Exception {
        OrderFixtures.StockProduct a = fixtures.inStockProduct(2);
        OrderFixtures.StockProduct b = fixtures.inStockProduct(1);
        Order mixed = place(customerId, "홍길동", "010-0000-0000", line(a, 0), line(b, 0));
        Order secondOption = place(customerId, "홍길동", "010-0000-0000", line(a, 1));

        assertListed(list("optionId", a.optionIds().get(0).toString()), mixed);
        assertListed(list("optionId", a.optionIds().get(1).toString()), secondOption);
        assertListed(list("productId", a.productId().toString(), "optionId", a.optionIds().get(0).toString()), mixed);
        assertListed(list("productId", b.productId().toString(), "optionId", b.optionIds().get(0).toString()), mixed);
        // 상품 A 와 상품 B 의 옵션은 한 주문에 있어도 같은 주문상품이 아니다
        assertListed(list("productId", a.productId().toString(), "optionId", b.optionIds().get(0).toString()));
    }

    @Test
    void createdRangeIsFromInclusiveToExclusive() throws Exception {
        UUID customer = fixtures.customer();
        Instant before = Instant.now().minusSeconds(1);
        Order first = orders.place(customer);
        Order second = orders.place(customer);
        Instant after = Instant.now().plusSeconds(1);
        Instant secondAt = createdAt(second);

        assertListed(list("customerId", customer.toString(), "from", before.toString(), "to", after.toString()), second, first);
        assertListed(list("customerId", customer.toString(), "from", secondAt.toString()), second);
        assertListed(list("customerId", customer.toString(), "to", secondAt.toString()), first);
        assertListed(list("customerId", customer.toString(), "from", after.toString()));
        // 오프셋이 있는 시각도 받는다
        assertListed(list("customerId", customer.toString(), "from", before.atOffset(ZoneOffset.ofHours(9)).toString()), second, first);
    }

    @Test
    void productFilterListsEachOrderOnceAndPagesByOrders() throws Exception {
        OrderFixtures.StockProduct product = fixtures.inStockProduct(2);
        OrderFixtures.StockProduct other = fixtures.inStockProduct(1);
        Order cart = placeCart(customerId, "홍길동", product, other);
        Order otherOnly = placeCart(customerId, "홍길동", other);
        Order single = placeCart(fixtures.customer(), "홍길동", product);

        assertListed(list("productId", product.productId().toString()), single, cart);
        assertListed(list("productId", product.productId().toString(), "customerId", customerId.toString()), cart);
        assertListed(list("productId", other.productId().toString()), otherOnly, cart);

        String firstPage = list("productId", product.productId().toString(), "size", "1");
        String secondPage = list("productId", product.productId().toString(), "size", "1", "page", "1");
        assertThat(JsonPath.<Integer>read(secondPage, "$.data.total")).isEqualTo(2);
        assertThat(ids(firstPage)).containsExactly(single.id().toString());
        assertThat(ids(secondPage)).containsExactly(cart.id().toString());
    }

    @Test
    void rowCarriesPreorderIdOnlyForPreorderOrders() throws Exception {
        Order preorder = orders.place(customerId);
        Order cart = placeCart(customerId, "홍길동", fixtures.inStockProduct(1));

        String body = list("customerId", customerId.toString());
        assertThat(JsonPath.<List<String>>read(body, "$.data.items[?(@.orderId == '%s')].preorderId"
                .formatted(preorder.id()))).containsExactly(OrderFixtures.preorderToken(preorder.preorderId()));
        assertThat(JsonPath.<List<Object>>read(body, "$.data.items[?(@.orderId == '%s')].preorderId"
                .formatted(cart.id()))).containsExactly((Object) null);

        // 사용자 목록 응답은 그대로다 — 예약 · 회원 칸이 없다
        mockMvc.perform(get("/api/v1/orders").with(TestAuth.customer(customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].orderId", contains(cart.id().toString(), preorder.id().toString())))
                .andExpect(jsonPath("$.data.items[0].preorderId").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].preorderId").doesNotExist())
                .andExpect(jsonPath("$.data.items[1].customerId").doesNotExist());
    }

    @Test
    void malformedSearchIsBadRequest() throws Exception {
        for (String field : List.of("customerId", "preorderId", "productId", "optionId", "from", "to")) {
            assertBadRequest(field, "not-a-value", field);
        }
        for (String q : List.of("1234567", "1234567g", "0".repeat(33))) {
            assertBadRequest("q", q, "q");
        }
        for (String last4 : List.of("123", "12345", "12a4")) {
            assertBadRequest("shipToPhoneLast4", last4, "shipToPhoneLast4");
        }
        assertBadRequest("shipToName", "가".repeat(51), "shipToName");
        mockMvc.perform(get("/api/v1/admin/orders").param("shipToName", "가".repeat(50)).with(admin()))
                .andExpect(status().isOk());
        Instant at = Instant.now();
        mockMvc.perform(get("/api/v1/admin/orders").param("from", at.toString()).param("to", at.toString()).with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("from"));
    }

    /** 전체 수는 모양만 본다 — 공유 DB 라 전역 수의 증감은 다른 시험이 흔든다. 수가 주문을 따르는지는 상품별 수로 본다. */
    @Test
    void statsListEveryStatusInOrder() throws Exception {
        orders.place(customerId);

        String body = stats(null);
        assertThat(JsonPath.<Map<String, Object>>read(body, "$.data.byStatus").keySet())
                .containsExactly(Arrays.stream(OrderStatus.values()).map(Enum::name).toArray(String[]::new));
        assertThat(JsonPath.<Map<String, Integer>>read(body, "$.data.byStatus").values()).allSatisfy(n -> assertThat(n).isNotNegative());
        assertThat(Instant.parse(JsonPath.read(body, "$.data.countedAt"))).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void statsOfProductCountOnlyOrdersContainingIt() throws Exception {
        OrderFixtures.StockProduct product = fixtures.inStockProduct(2);
        OrderFixtures.StockProduct other = fixtures.inStockProduct(1);
        fixtures.forceStatus(placeCart(customerId, "홍길동", product, other).id(), "SHIPPED");
        placeCart(customerId, "홍길동", product);
        placeCart(customerId, "홍길동", other);

        String body = stats(product.productId());
        Map<String, Integer> byStatus = JsonPath.read(body, "$.data.byStatus");
        assertThat(byStatus.keySet()).containsExactly(Arrays.stream(OrderStatus.values()).map(Enum::name).toArray(String[]::new));
        assertThat(byStatus.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(2);
        assertThat(byStatus.get("SHIPPED")).isEqualTo(1);
        assertThat(statusCount(UUID.randomUUID(), "SHIPPED")).isZero();
    }

    @Test
    void userTokenIsForbidden() throws Exception {
        Order order = orders.place(customerId);

        mockMvc.perform(get("/api/v1/admin/orders").with(TestAuth.customer(customerId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/orders/" + order.id().toString())
                        .with(TestAuth.customer(customerId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/orders/stats").with(TestAuth.customer(customerId)))
                .andExpect(status().isForbidden());
    }

    /** 장바구니 주문. 상품마다 그 상품의 옵션을 모두 한 줄씩 담는다. */
    private Order placeCart(UUID customer, String shipToName, OrderFixtures.StockProduct... products) {
        return place(customer, shipToName, "010-0000-0000", Arrays.stream(products)
                .flatMap(product -> product.optionIds().stream().map(option -> line(product.productId(), option)))
                .toArray(PlaceOrderCommand.Line[]::new));
    }

    private Order place(UUID customer, String shipToName, String shipToPhone, PlaceOrderCommand.Line... lines) {
        PlaceOrderCommand.Address address = new PlaceOrderCommand.Address(shipToName, shipToPhone, "04524", "서울시 중구 세종대로 110", null);
        return transactionTemplate.execute(status -> ledger.place(
                new PlaceOrderCommand(customer, OrderSource.CART, null, null, null, address, List.of(lines)).toDraft(), EventCause.user()));
    }

    /** 그 상품의 index 번째 옵션 한 줄. */
    private static PlaceOrderCommand.Line line(OrderFixtures.StockProduct product, int index) {
        return line(product.productId(), product.optionIds().get(index));
    }

    private static PlaceOrderCommand.Line line(UUID productId, UUID optionId) {
        return new PlaceOrderCommand.Line(productId, optionId, 1, OrderFixtures.UNIT_PRICE, 0, BigDecimal.ZERO, OrderFixtures.PRODUCT_TITLE,
                null, OrderFixtures.OPTION_TITLE, null);
    }

    /** 저장된 주문 시각(UTC). JDBC 의 Timestamp 는 JVM 시간대로 읽혀 어긋나므로 문자열로 받는다. */
    private Instant createdAt(Order order) {
        return Instant.parse(jdbcTemplate.queryForObject("SELECT DATE_FORMAT(created_at, '%Y-%m-%dT%H:%i:%s.%fZ') FROM orders WHERE id = ?",
                String.class, (Object) OrderFixtures.bytes(order.id())));
    }

    private void assertBadRequest(String param, String value, String field) throws Exception {
        mockMvc.perform(get("/api/v1/admin/orders").param(param, value).with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value(field));
    }

    /** 관리자 목록. params 는 이름 · 값을 번갈아. size 를 주지 않으면 한 쪽 100줄. */
    private String list(String... params) throws Exception {
        var request = get("/api/v1/admin/orders").with(admin());
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        if (!List.of(params).contains("size")) {
            request.param("size", "" + PageSizes.MAX);
        }
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static List<String> ids(String body) {
        return JsonPath.read(body, "$.data.items[*].orderId");
    }

    /** 조건에 맞는 주문이 정확히 이것들(최신순)이고 총계도 그 수다. */
    private static void assertListed(String body, Order... expected) {
        assertThat(ids(body)).containsExactly(Arrays.stream(expected).map(order -> order.id().toString()).toArray(String[]::new));
        assertThat(JsonPath.<Integer>read(body, "$.data.total")).isEqualTo(expected.length);
    }

    private String stats(UUID productId) throws Exception {
        var request = get("/api/v1/admin/orders/stats").with(admin());
        if (productId != null) {
            request.param("productId", productId.toString());
        }
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private long statusCount(UUID productId, String status) throws Exception {
        return JsonPath.<Number>read(stats(productId), "$.data.byStatus." + status).longValue();
    }

    private static RequestPostProcessor admin() {
        return TestAuth.admin();
    }
}
