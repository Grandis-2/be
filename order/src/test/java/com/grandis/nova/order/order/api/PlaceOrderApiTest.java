package com.grandis.nova.order.order.api;

import com.grandis.nova.order.client.preorder.PreorderClient;
import com.grandis.nova.order.client.preorder.PreorderSnapshot;
import com.grandis.nova.order.support.Concurrently;
import com.grandis.nova.order.support.Concurrently.Outcome;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderFixtures.PreorderProduct;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.PreorderStubs;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/orders (source=PREORDER). MySQL 위에서 돌고 preorder 내부 API 만 대역이다.
 *
 * common:security 도입 시: 인증 흉내를 user(...).roles(...) 에서 NovaAuthentication 으로 바꾼다
 * (authentication(new NovaAuthentication(new AuthenticatedPrincipal(customerId.toString(), Role.USER))),
 * ADMIN 은 ("admin", Role.ADMIN)). common:security 의 리졸버는
 * NovaAuthentication 만 인증으로 보므로 그대로 두면 모든 요청이 401 이 된다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
class PlaceOrderApiTest {

    // 에픽 완료 조건(NV-45 §5): 동시 100건 → 주문 1건.
    static final int CONCURRENT_REQUESTS = 100;

    // 같은 상품 안에서 순번이 겹치면 안 된다(uq_preorder_position). 테스트마다 상품을 새로 만들지만 한 테스트 안에서 예약을 여럿 만든다.
    static final AtomicLong QUEUE_POSITION = new AtomicLong();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    PreorderClient preorderClient;

    OrderFixtures fixtures;
    PreorderProduct product;
    Long customerId;
    Long preorderId;
    String token;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        product = fixtures.preorderProduct();
        customerId = fixtures.customer();
        preorderId = fixtures.payablePreorder(customerId, product, QUEUE_POSITION.incrementAndGet());
        token = OrderFixtures.unique();
    }

    @Test
    void createsOrderFromPayablePreorder() throws Exception {
        stubPreorder("PAYABLE", Instant.now().minus(Duration.ofHours(1)));

        MvcResult result = place(customerId, token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.data.source").value("PREORDER"))
                .andExpect(jsonPath("$.data.totalAmount").value(1250000))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].optionId").value(product.optionId()))
                .andExpect(jsonPath("$.data.items[0].productTitle").value(OrderFixtures.PRODUCT_TITLE))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(1250000))
                .andExpect(jsonPath("$.data.items[0].quantity").value(1))
                .andExpect(jsonPath("$.data.shipTo.name").value("홍길동"))
                .andExpect(jsonPath("$.data.shipTo.line2").doesNotExist())
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andReturn();

        String orderId = orderIdOf(result);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/v1/orders/" + orderId);
        assertThat(count("SELECT COUNT(*) FROM orders WHERE preorder_id = ? AND order_token = ?", preorderId, orderId))
                .isEqualTo(1);
        assertThat(count("""
                SELECT COUNT(*) FROM order_items i JOIN orders o ON o.id = i.order_id WHERE o.preorder_id = ?
                """, preorderId)).isEqualTo(1);
        assertThat(count("""
                SELECT COUNT(*) FROM order_events e JOIN orders o ON o.id = e.order_id
                WHERE o.preorder_id = ? AND e.actor = 'USER' AND e.from_status IS NULL
                """, preorderId)).isEqualTo(1);
    }

    @Test
    void repeatedRequestReturnsSameOrderWith200() throws Exception {
        stubPreorder("PAYABLE", Instant.now().minus(Duration.ofHours(1)));
        String first = orderIdOf(place(customerId, token).andExpect(status().isCreated()).andReturn());

        place(customerId, token)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.data.orderId").value(first))
                .andExpect(jsonPath("$.data.items.length()").value(1));

        assertThat(orderCount()).isEqualTo(1);
    }

    // 주문한 뒤 기한이 지나도 재요청은 같은 주문이다. 응답이 시간에 흔들리지 않는다.
    @Test
    void repeatedRequestAfterWindowStillReturnsExistingOrder() throws Exception {
        stubPreorder("PAYABLE", Instant.now().minus(Duration.ofHours(1)));
        String first = orderIdOf(place(customerId, token).andExpect(status().isCreated()).andReturn());
        stubPreorder("PAYABLE", Instant.now().minus(Duration.ofHours(25)));

        place(customerId, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(first));
    }

    @Test
    void concurrentRequestsCreateOneOrder() throws Exception {
        stubPreorder("PAYABLE", Instant.now().minus(Duration.ofHours(1)));

        List<Outcome<MvcResult>> outcomes = Concurrently.run(CONCURRENT_REQUESTS,
                i -> () -> place(customerId, token).andReturn());

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).isNull());
        List<Integer> statuses = outcomes.stream().map(o -> o.value().getResponse().getStatus()).toList();
        assertThat(statuses).containsOnly(201, 200);
        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
        List<String> orderIds = outcomes.stream().map(o -> orderIdOf(o.value())).distinct().toList();
        assertThat(orderIds).hasSize(1);
        assertThat(orderCount()).isEqualTo(1);
    }

    // 남의 예약이 있다는 사실도 알리지 않는다.
    @Test
    void someoneElsesPreorderIsNotFound() throws Exception {
        stubPreorder("PAYABLE", Instant.now().minus(Duration.ofHours(1)));
        Long stranger = fixtures.customer();

        place(stranger, token)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PREORDER_NOT_FOUND"));

        assertThat(orderCount()).isZero();
    }

    /*
     * 주문이 이미 있어도 남의 요청에는 그 주문(배송지)을 돌려주지 않는다. 기존 주문 확인이 본인 확인보다 앞으로 옮겨지면
     * 200 과 함께 남의 주문이 나간다 — 그 회귀를 막는다.
     */
    @Test
    void someoneElsesPreorderIsNotFoundEvenAfterOrdered() throws Exception {
        stubPreorder("PAYABLE", Instant.now().minus(Duration.ofHours(1)));
        place(customerId, token).andExpect(status().isCreated());
        Long stranger = fixtures.customer();

        place(stranger, token)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PREORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());

        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    void unknownPreorderIsNotFound() throws Exception {
        PreorderStubs.stubNotFound(preorderClient, token);

        place(customerId, token)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PREORDER_NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING_SYNC", "CANCELING", "CANCELED"})
    void preorderThatIsNotPayableIsConflict(String status) throws Exception {
        stubPreorder(status, "PENDING_SYNC".equals(status) ? null : Instant.now().minus(Duration.ofHours(1)));

        place(customerId, token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PREORDER_NOT_PAYABLE"));

        assertThat(orderCount()).isZero();
    }

    @Test
    void expiredPaymentWindowIsConflict() throws Exception {
        stubPreorder("PAYABLE", Instant.now().minus(Duration.ofHours(25)));

        place(customerId, token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_WINDOW_EXPIRED"));

        assertThat(orderCount()).isZero();
    }

    // 예약당 주문은 평생 하나다. 취소된 주문이 있으면 다시 만들 수 없다.
    @Test
    void canceledOrderCannotBePlacedAgain() throws Exception {
        stubPreorder("PAYABLE", Instant.now().minus(Duration.ofHours(1)));
        String orderId = orderIdOf(place(customerId, token).andExpect(status().isCreated()).andReturn());
        fixtures.forceStatus(jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE order_token = ?", Long.class, orderId), "CANCELED");

        place(customerId, token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ORDER_ALREADY_CANCELED"));
    }

    @Test
    void preorderTimeoutIsServiceUnavailable() throws Exception {
        PreorderStubs.stubTimeout(preorderClient, token);

        place(customerId, token)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"));

        assertThat(orderCount()).isZero();
    }

    @Test
    void unauthenticatedIs401() throws Exception {
        mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(body("PREORDER", token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminIs403() throws Exception {
        mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(body("PREORDER", token))
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void otherSourcesAreRejected() throws Exception {
        perform(customerId, body("BUY_NOW", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("source"));
    }

    @Test
    void preorderIdIsRequired() throws Exception {
        perform(customerId, """
                {"source":"PREORDER","shipTo":%s}
                """.formatted(SHIP_TO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("preorderId"));
    }

    @Test
    void preorderIdMustBeToken() throws Exception {
        perform(customerId, body("PREORDER", "not-a-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("preorderId"));
    }

    @Test
    void blankRecipientIsRejected() throws Exception {
        perform(customerId, """
                {"source":"PREORDER","preorderId":"%s",
                 "shipTo":{"name":" ","phone":"010-0000-0000","postalCode":"04524","line1":"서울시 중구 세종대로 110"}}
                """.formatted(token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("shipTo.name"));
    }

    static final String SHIP_TO = """
            {"name":"홍길동","phone":"010-0000-0000","postalCode":"04524","line1":"서울시 중구 세종대로 110"}""";

    private void stubPreorder(String status, Instant payableFrom) {
        PreorderSnapshot snapshot = PreorderStubs.snapshot(preorderId, token, customerId, product, status, payableFrom);
        PreorderStubs.stub(preorderClient, snapshot);
    }

    private ResultActions place(Long customer, String preorderToken) throws Exception {
        return perform(customer, body("PREORDER", preorderToken));
    }

    private ResultActions perform(Long customer, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(json)
                .with(user(customer.toString()).roles("USER")));
    }

    private static String body(String source, String preorderToken) {
        return """
                {"source":"%s","preorderId":"%s","shipTo":%s}
                """.formatted(source, preorderToken, SHIP_TO);
    }

    private static String orderIdOf(MvcResult result) {
        try {
            return JsonPath.read(result.getResponse().getContentAsString(), "$.data.orderId");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private int orderCount() {
        return count("SELECT COUNT(*) FROM orders WHERE preorder_id = ?", preorderId);
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }
}
