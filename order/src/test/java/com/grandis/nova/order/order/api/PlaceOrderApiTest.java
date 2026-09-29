package com.grandis.nova.order.order.api;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.order.client.preorder.PreorderClient;
import com.grandis.nova.order.support.Concurrently;
import com.grandis.nova.order.support.Concurrently.Outcome;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderFixtures.PreorderProduct;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.PreorderStubs;
import com.grandis.nova.order.support.TestAuth;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/orders (source=PREORDER). MySQL 위에서 돌고 preorder 내부 API 만 대역이다.
 * 대역 응답은 preorder 결제 가능 확인 계약과 같은 모양이다(계약 대조는 PreorderClientTest).
 *
 * 인증은 흉내({@link TestAuth})다 — 필터가 토큰을 읽지 않으므로 Authorization 헤더의 값은 전달 확인용 자리일 뿐이다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
class PlaceOrderApiTest {

    // 에픽 완료 조건(NV-45 §5): 동시 100건 → 주문 1건.
    static final int CONCURRENT_REQUESTS = 100;

    // 같은 상품 안에서 순번이 겹치면 안 된다(uq_preorder_position). 테스트마다 상품을 새로 만들지만 한 테스트 안에서 예약을 여럿 만든다.
    static final AtomicLong QUEUE_POSITION = new AtomicLong();

    // 사용자가 보낸 액세스 토큰 자리(Authorization: Bearer 의 값). preorder 에 그대로 전달돼야 한다. 실제 토큰 모양이 아니다.
    static final String SESSION = "place-order-api-test";

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
        stubPayable();

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
        stubPayable();
        String first = orderIdOf(place(customerId, token).andExpect(status().isCreated()).andReturn());

        place(customerId, token)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.data.orderId").value(first))
                .andExpect(jsonPath("$.data.items.length()").value(1));

        assertThat(orderCount()).isEqualTo(1);
    }

    // 주문한 뒤 기한이 지나도(preorder 가 DUE_PASSED 로 답해도) 재요청은 같은 주문이다. 응답이 시간에 흔들리지 않는다.
    @Test
    void repeatedRequestAfterWindowStillReturnsExistingOrder() throws Exception {
        stubPayable();
        String first = orderIdOf(place(customerId, token).andExpect(status().isCreated()).andReturn());
        stubBlocked("DUE_PASSED");

        place(customerId, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(first));
    }

    @Test
    void concurrentRequestsCreateOneOrder() throws Exception {
        stubPayable();

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

    // preorder 는 남의 예약을 403 으로 거절한다. 사용자에게는 존재를 숨겨 404 다.
    @Test
    void preorderForbiddenIsNotFound() throws Exception {
        PreorderStubs.stubClientError(preorderClient, token, HttpStatus.FORBIDDEN);

        place(customerId, token)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PREORDER_NOT_FOUND"));

        assertThat(orderCount()).isZero();
    }

    // preorder 가 토큰을 거절하면(서명 · 만료) 사용자에게도 401 이다.
    @Test
    void preorderUnauthorizedIsUnauthorized() throws Exception {
        PreorderStubs.stubClientError(preorderClient, token, HttpStatus.UNAUTHORIZED);

        place(customerId, token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        assertThat(orderCount()).isZero();
    }

    // 사용자가 보낸 액세스 토큰을 그대로 preorder 에 Authorization: Bearer 로 싣는다(새로 발급하지 않는다).
    @Test
    void forwardsAccessTokenToPreorder() throws Exception {
        stubPayable();

        place(customerId, token).andExpect(status().isCreated());

        verify(preorderClient).getPayability(token, BearerTokens.value(SESSION));
    }

    // 옛 헤더(X-Session-Token)의 토큰은 읽지도 전달하지도 않는다(NV-137) — 싣지 않으면 preorder 가 401 로 판단한다.
    @Test
    void legacySessionHeaderIsNotForwarded() throws Exception {
        stubPayable();

        mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(body("PREORDER", token))
                        .header("X-Session-Token", SESSION)
                        .with(TestAuth.customer(customerId)))
                .andExpect(status().isCreated());

        verify(preorderClient).getPayability(token, null);
    }

    /*
     * 이중 방어: preorder 가 403 을 주지 않고 남의 예약을 돌려줘도(주인 확인 누락 등) order 가 회원을 다시 대조해 숨긴다.
     * 남의 예약이 있다는 사실도 알리지 않는다.
     */
    @Test
    void someoneElsesPreorderIsNotFound() throws Exception {
        stubPayable();
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
        stubPayable();
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
        PreorderStubs.stubPreorderNotFound(preorderClient, token);

        place(customerId, token)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PREORDER_NOT_FOUND"));
    }

    // preorder 에 경로가 없으면(배포 순서 · 주소 오류) "예약 없음" 으로 숨기지 않고 연동 오류로 드러낸다.
    @Test
    void preorderRouteNotFoundIsInternalError() throws Exception {
        PreorderStubs.stubRouteNotFound(preorderClient, token);

        place(customerId, token)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));

        assertThat(orderCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_YET_REGISTERED", "CANCELING", "CANCELED"})
    void preorderThatIsNotPayableIsConflict(String reason) throws Exception {
        stubBlocked(reason);

        place(customerId, token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PREORDER_NOT_PAYABLE"));

        assertThat(orderCount()).isZero();
    }

    @Test
    void expiredPaymentWindowIsConflict() throws Exception {
        stubBlocked("DUE_PASSED");

        place(customerId, token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_WINDOW_EXPIRED"));

        assertThat(orderCount()).isZero();
    }

    // 예약당 주문은 평생 하나다. 취소된 주문이 있으면 다시 만들 수 없다.
    @Test
    void canceledOrderCannotBePlacedAgain() throws Exception {
        stubPayable();
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

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503})
    void preorderServerErrorIsServiceUnavailable(int status) throws Exception {
        PreorderStubs.stubServerError(preorderClient, token, HttpStatus.valueOf(status));

        place(customerId, token)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"));

        assertThat(orderCount()).isZero();
    }

    // 그 밖의 4xx 는 사용자 잘못이 아니라 연동 오류라 500 이다(상대 상태를 그대로 돌려주지 않는다).
    @Test
    void otherPreorderClientErrorIsInternalError() throws Exception {
        PreorderStubs.stubClientError(preorderClient, token, HttpStatus.BAD_REQUEST);

        place(customerId, token)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));

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
                        .with(TestAuth.admin()))
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

    private void stubPayable() {
        PreorderStubs.stub(preorderClient, PreorderStubs.payable(preorderId, token, customerId, product));
    }

    private void stubBlocked(String reason) {
        PreorderStubs.stub(preorderClient, PreorderStubs.blocked(preorderId, token, customerId, product, reason));
    }

    private ResultActions place(Long customer, String preorderToken) throws Exception {
        return perform(customer, body("PREORDER", preorderToken));
    }

    private ResultActions perform(Long customer, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(json)
                .header(BearerTokens.HEADER, BearerTokens.value(SESSION))
                .with(TestAuth.customer(customer)));
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
