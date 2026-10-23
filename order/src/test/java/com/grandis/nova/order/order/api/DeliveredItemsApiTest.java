package com.grandis.nova.order.order.api;

import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.command.PlaceOrderCommand;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.query.OrderQueryService;
import com.grandis.nova.order.order.vo.EventCause;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** catalog 가 리뷰를 쓸 수 있는 주문상품을 고를 때 부르는 배송 완료 구매 주문상품(contracts/order-internal.md). 회원 본인 것만, 사실만 돌려준다. */
@OrderIntegrationTest
@AutoConfigureMockMvc
class DeliveredItemsApiTest {

    private static final String PATH = "/internal/order-items/delivered";

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

    /* 배송 완료된 장바구니 · 바로 구매 주문의 주문상품만. 사전예약 · 드로우 · 배송 전 · 남의 주문은 빠진다. */
    @Test
    void returnsItemsOfMyDeliveredPurchasesOnly() throws Exception {
        OrderFixtures.StockProduct product = fixtures.inStockProduct(2);
        Order older = place(customerId, OrderSource.CART, product);
        Order newer = place(customerId, OrderSource.CART, product);
        Order shipped = place(customerId, OrderSource.CART, product);
        Order others = place(fixtures.customer(), OrderSource.CART, product);
        Order preorder = orders.place(customerId);
        List.of(older, newer, others, preorder).forEach(order -> fixtures.forceStatus(order.id(), "DELIVERED"));
        fixtures.forceStatus(shipped.id(), "SHIPPED");
        List<String> olderItems = itemIds(older);
        List<String> newerItems = itemIds(newer);

        mockMvc.perform(get(PATH).with(me()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].orderItemId",
                        contains(newerItems.get(0), newerItems.get(1), olderItems.get(0), olderItems.get(1))))
                .andExpect(jsonPath("$.data.items[0].orderId").value(newer.id().toString()))
                .andExpect(jsonPath("$.data.items[0].productId").value(product.productId().toString()))
                .andExpect(jsonPath("$.data.items[0].productTitle").value(OrderFixtures.PRODUCT_TITLE))
                .andExpect(jsonPath("$.data.items[0].optionTitle").value(OrderFixtures.OPTION_TITLE))
                .andExpect(jsonPath("$.data.items[0].imageUrl").value("https://img.test/a.webp"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(1))
                .andExpect(jsonPath("$.data.items[0].lineAmount").value(OrderFixtures.UNIT_PRICE.intValue()))
                .andExpect(jsonPath("$.data.items[0].orderedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.truncated").value(false));
    }

    @Test
    void drawOrdersAreLeftOut() throws Exception {
        Order draw = place(customerId, OrderSource.CART, fixtures.inStockProduct(1));
        fixtures.forceStatus(draw.id(), "DELIVERED");
        jdbcTemplate.update("UPDATE orders SET source = 'DRAW', payment_due_at = NULL WHERE id = ?", bytes(draw.id()));

        mockMvc.perform(get(PATH).with(me()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    /* 최근 주문 상한을 넘으면 오래된 주문은 빠지고 truncated 다. */
    @Test
    void readsUpToTheRecentOrderCap() throws Exception {
        OrderFixtures.StockProduct product = fixtures.inStockProduct(1);
        Order first = place(customerId, OrderSource.BUY_NOW, product);
        fixtures.forceStatus(first.id(), "DELIVERED");
        jdbcTemplate.update("UPDATE orders SET created_at = created_at - INTERVAL 1 DAY WHERE id = ?", bytes(first.id()));
        for (int i = 0; i < OrderQueryService.DELIVERED_PURCHASES_MAX; i++) {
            fixtures.forceStatus(place(customerId, OrderSource.CART, product).id(), "DELIVERED");
        }

        mockMvc.perform(get(PATH).with(me()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(OrderQueryService.DELIVERED_PURCHASES_MAX)))
                .andExpect(jsonPath("$.data.items[?(@.orderId == '%s')]".formatted(first.id()), hasSize(0)))
                .andExpect(jsonPath("$.data.truncated").value(true));
    }

    @Test
    void customerOnly() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH).with(TestAuth.admin())).andExpect(status().isForbidden());
    }

    private Order place(UUID customer, OrderSource source, OrderFixtures.StockProduct product) {
        PlaceOrderCommand.Address address = new PlaceOrderCommand.Address("홍길동", "010-0000-0000", "04524", "서울시 중구 세종대로 110", null);
        List<PlaceOrderCommand.Line> lines = product.optionIds().stream()
                .map(option -> new PlaceOrderCommand.Line(product.productId(), option, 1, OrderFixtures.UNIT_PRICE, 0, BigDecimal.ZERO,
                        OrderFixtures.PRODUCT_TITLE, null, OrderFixtures.OPTION_TITLE, "https://img.test/a.webp"))
                .toList();
        UUID idempotencyKey = source == OrderSource.BUY_NOW ? UUID.randomUUID() : null;
        return transactionTemplate.execute(status -> ledger.place(
                new PlaceOrderCommand(customer, source, null, null, idempotencyKey, address, lines).toDraft(), EventCause.user()));
    }

    private List<String> itemIds(Order order) {
        return jdbcTemplate.queryForList("SELECT BIN_TO_UUID(id) FROM order_items WHERE order_id = ? ORDER BY id", String.class,
                bytes(order.id()));
    }

    private RequestPostProcessor me() {
        return TestAuth.customer(customerId);
    }
}
