package com.grandis.nova.order.order.api;

import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.cart.domain.repository.CartStore;
import com.grandis.nova.order.client.catalog.CatalogClient;
import com.grandis.nova.order.client.catalog.CatalogOption;
import com.grandis.nova.order.client.catalog.CatalogOptions;
import com.grandis.nova.order.client.payment.DeclineReason;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.pay.PaymentResults;
import com.grandis.nova.order.order.pay.PaymentSettlement;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.TestAuth;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 바로 구매 주문(POST /api/v1/orders, source=BUY_NOW) — 생성 · 멱등 키(Idempotency-Key) · 결제 결과 반영 · 미결제 취소, 장바구니 주문과의 독립.
 * 따로 키를 주지 않은 요청은 누름마다 새 키다.
 * 기한 만료 · 만료와 사용자 취소의 겹침은 CartOrderCancelTest 가 본다(만료 처리의 진입점이 패키지 안이다).
 * 대역 구성은 CartOrderApiTest · CartOrderCancelTest 와 같다 — Spring 컨텍스트(와 MySQL 컨테이너)를 함께 쓴다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
class BuyNowOrderApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final BigDecimal PRICE = new BigDecimal("1250000");
    static final BigDecimal WARRANTY = new BigDecimal("199000");
    static final String SHIP_TO = """
            {"name":"홍길동","phone":"010-0000-0000","postalCode":"04524","line1":"서울시 중구 세종대로 110"}""";
    static final String OTHER_SHIP_TO = """
            {"name":"홍길동","phone":"010-0000-0000","postalCode":"48058","line1":"부산시 해운대구 센텀로 1"}""";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PaymentResults results;
    @Autowired OrderReader orderReader;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean CatalogClient catalogClient;
    /** 쓰지 않지만 CartApiTest 와 같은 대역 구성으로 두어 Spring 컨텍스트를 함께 쓴다. */
    @MockitoSpyBean CartStore store;

    OrderFixtures fixtures;
    UUID customerId;
    Map<UUID, CatalogOption> catalog = new HashMap<>();

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        customerId = fixtures.customer();
        given(catalogClient.getOptions(any(), any())).willAnswer(invocation -> {
            Collection<UUID> ids = invocation.getArgument(0);
            return ApiResponse.ok(new CatalogOptions(ids.stream().filter(catalog::containsKey).map(catalog::get).toList()));
        });
    }

    @Test
    @DisplayName("옵션 하나의 보증 포함 · 미포함 줄로 주문 — 주문상품 한 줄로 합치고, 재고를 확보하고, 기한은 10분(201). 장바구니는 읽지도 바꾸지도 않는다")
    void placesBuyNowOrderReservingStock() throws Exception {
        UUID phone = sellable(10);
        cartLine(customerId, phone, false, 1);
        Instant before = Instant.now();

        JsonNode order = data(place(item(phone, 2, false), item(phone, 1, true)).andExpect(status().isCreated()));

        assertThat(order.get("source").asString()).isEqualTo("BUY_NOW");
        assertThat(order.get("status").asString()).isEqualTo("AWAITING_PAYMENT");
        assertThat(order.get("reused").asBoolean()).isFalse();
        assertThat(order.get("totalAmount").decimalValue()).isEqualByComparingTo(PRICE.multiply(BigDecimal.valueOf(3)).add(WARRANTY));
        Instant due = Instant.parse(order.get("paymentDueAt").asString());
        assertThat(due).isBetween(before.plus(Duration.ofMinutes(10)).minusSeconds(1), Instant.now().plus(Duration.ofMinutes(10)));
        JsonNode items = order.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("quantity").asInt()).isEqualTo(3);
        assertThat(items.get(0).get("warrantyQuantity").asInt()).isEqualTo(1);
        assertThat(reserved(phone)).isEqualTo(3);
        assertThat(cart(customerId)).as("장바구니는 그대로").containsExactlyEntriesOf(Map.of(phone + "/false", 1));
    }

    @Test
    @DisplayName("누를 때마다(키가 다르면) 새 주문이다 — 같은 내용이어도 기존 주문을 돌려주지 않고 앞 주문도 취소하지 않는다")
    void everyRequestCreatesNewOrder() throws Exception {
        UUID phone = sellable(10);

        String first = data(place(item(phone, 1, false)).andExpect(status().isCreated())).get("orderId").asString();
        String second = data(place(item(phone, 1, false)).andExpect(status().isCreated())).get("orderId").asString();

        assertThat(second).isNotEqualTo(first);
        assertThat(orderStatus(first)).isEqualTo("AWAITING_PAYMENT");
        assertThat(reserved(phone)).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 키 · 같은 내용의 재전송은 200 reused 와 같은 주문 — 주문 · 확보는 한 번. 그사이 값이 바뀌었어도 가격은 대조하지 않는다")
    void sameKeySameRequestReplaysOrder() throws Exception {
        UUID phone = sellable(10);
        UUID key = UUID.randomUUID();
        JsonNode first = data(placeWith(key, item(phone, 2, false), item(phone, 1, true)).andExpect(status().isCreated()));
        catalog.put(phone, copy(catalog.get(phone), "PAUSED", true));

        JsonNode again = data(placeWith(key, item(phone, 1, true), item(phone, 2, false)).andExpect(status().isOk()));

        assertThat(again.get("reused").asBoolean()).isTrue();
        assertThat(again.get("orderId").asString()).isEqualTo(first.get("orderId").asString());
        assertThat(again.get("status").asString()).isEqualTo("AWAITING_PAYMENT");
        assertThat(orderCount()).isEqualTo(1);
        assertThat(reserved(phone)).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 키인데 내용(수량 · 보증 수량 · 옵션 · 배송지)이 다르면 422 KEY_PAYLOAD_MISMATCH — 앞 주문 · 확보는 그대로")
    void sameKeyDifferentRequestIs422() throws Exception {
        UUID phone = sellable(10);
        UUID watch = sellable(10);
        UUID key = UUID.randomUUID();
        String first = data(placeWith(key, item(phone, 2, false)).andExpect(status().isCreated())).get("orderId").asString();

        expectError(placeWith(key, item(phone, 3, false)), 422, "KEY_PAYLOAD_MISMATCH");
        expectError(placeWith(key, item(phone, 1, false), item(phone, 1, true)), 422, "KEY_PAYLOAD_MISMATCH");
        expectError(placeWith(key, item(watch, 2, false)), 422, "KEY_PAYLOAD_MISMATCH");
        expectError(perform("""
                {"source":"BUY_NOW","items":[%s],"shipTo":%s}""".formatted(item(phone, 2, false), OTHER_SHIP_TO), key), 422, "KEY_PAYLOAD_MISMATCH");

        assertThat(orderCount()).isEqualTo(1);
        assertThat(orderStatus(first)).isEqualTo("AWAITING_PAYMENT");
        assertThat(reserved(phone)).isEqualTo(2);
        assertThat(reserved(watch)).isZero();
    }

    /**
     * 같은 키가 동시에 오면 하나만 만들고 다른 하나는 그 주문을 돌려받는다 — 결정적으로 본다: 다른 트랜잭션이 재고 행을 잠근 채, 두 요청이 모두
     * 앞의 찾기를 지나 확보에서 기다리는 것을 확인한 뒤 놓는다. 뒤의 것은 재고가 남으면 유일 키 중복으로, 마지막 재고였으면 재고 부족으로
     * 끝나는데 — 어느 쪽이든 그 키의 주문이 있으므로 200 reused 다.
     */
    @ParameterizedTest
    @ValueSource(ints = {2, 10})
    void concurrentSameKeyCreatesOneOrder(int stock) throws Exception {
        UUID phone = sellable(stock);
        UUID key = UUID.randomUUID();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            jdbcTemplate.queryForList("SELECT option_id FROM option_inventories WHERE option_id = ? FOR UPDATE", (Object) bytes(phone));
            locked.countDown();
            await(release);
        }));
        await(locked);
        List<CompletableFuture<Integer>> requests = IntStream.range(0, 2).mapToObj(i -> CompletableFuture.supplyAsync(() -> {
            try {
                return placeWith(key, item(phone, 2, false)).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        })).toList();
        Awaitility.await("두 요청이 확보에서 기다리지 않았다").atMost(Duration.ofSeconds(10)).until(() -> jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.PROCESSLIST
                 WHERE COMMAND = 'Query' AND INFO LIKE 'UPDATE option_inventories SET stock_reserved = stock_reserved +%'""", Long.class) >= 2);
        release.countDown();
        holder.get(20, TimeUnit.SECONDS);

        List<Integer> statuses = new ArrayList<>();
        for (CompletableFuture<Integer> request : requests) {
            statuses.add(request.get(20, TimeUnit.SECONDS));
        }
        assertThat(statuses).containsExactlyInAnyOrder(201, 200);
        assertThat(orderCount()).isEqualTo(1);
        assertThat(reserved(phone)).isEqualTo(2);
    }

    @Test
    @DisplayName("앞 요청이 실패해(재고 부족 — 롤백) 그 키의 주문이 없으면 같은 키의 다음 요청이 새로 만든다")
    void sameKeyAfterFailedAttemptCreatesOrder() throws Exception {
        UUID phone = sellable(1);
        UUID key = UUID.randomUUID();
        expectError(placeWith(key, item(phone, 2, false)), 409, "INSUFFICIENT_STOCK");
        jdbcTemplate.update("UPDATE option_inventories SET stock_total = 5 WHERE option_id = ?", (Object) bytes(phone));

        placeWith(key, item(phone, 2, false)).andExpect(status().isCreated());

        assertThat(orderCount()).isEqualTo(1);
        assertThat(reserved(phone)).isEqualTo(2);
    }

    @Test
    @DisplayName("키는 회원 안에서 유일하다 — 다른 회원의 같은 키는 다른 주문")
    void sameKeyOfAnotherCustomerIsAnotherOrder() throws Exception {
        UUID phone = sellable(10);
        UUID key = UUID.randomUUID();
        String mine = data(placeWith(key, item(phone, 1, false)).andExpect(status().isCreated())).get("orderId").asString();

        String others = data(mockMvc.perform(post("/api/v1/orders").with(TestAuth.customer(fixtures.customer()))
                .contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key.toString())
                .content("""
                        {"source":"BUY_NOW","items":[%s],"shipTo":%s}""".formatted(item(phone, 1, false), SHIP_TO)))
                .andExpect(status().isCreated())).get("orderId").asString();

        assertThat(others).isNotEqualTo(mine);
        assertThat(reserved(phone)).isEqualTo(2);
    }

    @Test
    @DisplayName("Idempotency-Key 가 없으면 400 IDEMPOTENCY_KEY_REQUIRED, UUID 가 아니면 400 VALIDATION_FAILED — 주문 · 확보 없음")
    void keyIsRequiredAsUuid() throws Exception {
        UUID phone = sellable(10);
        String body = """
                {"source":"BUY_NOW","items":[%s],"shipTo":%s}""".formatted(item(phone, 1, false), SHIP_TO);

        expectError(perform(body, null), 400, "IDEMPOTENCY_KEY_REQUIRED");
        expectViolation(mockMvc.perform(post("/api/v1/orders").with(TestAuth.customer(customerId)).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "not-a-uuid").content(body)), "Idempotency-Key");
        expectViolation(mockMvc.perform(post("/api/v1/orders").with(TestAuth.customer(customerId)).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "1-1-1-1-1").content(body)), "Idempotency-Key");

        assertThat(orderCount()).isZero();
        assertThat(reserved(phone)).isZero();
    }

    @Test
    @DisplayName("장바구니 주문은 Idempotency-Key 를 보지 않는다 — 같은 키를 실어도 장바구니 규칙대로이고, 그 키는 바로 구매에서 새로 쓸 수 있다")
    void cartOrderIgnoresKey() throws Exception {
        UUID phone = sellable(10);
        UUID watch = sellable(10);
        UUID key = UUID.randomUUID();
        cartLine(customerId, phone, false, 1);
        cartLine(customerId, watch, false, 1);
        String cartBody = """
                {"source":"CART","items":[%s],"shipTo":%s}""";

        String first = data(perform(cartBody.formatted(item(phone, 1, false), SHIP_TO), key).andExpect(status().isCreated())).get("orderId").asString();
        perform(cartBody.formatted(item(watch, 1, false), SHIP_TO), key).andExpect(status().isCreated());
        placeWith(key, item(phone, 1, false)).andExpect(status().isCreated());

        assertThat(orderStatus(first)).as("장바구니 규칙(회원당 미결제 하나)대로 바뀌었다").isEqualTo("CANCELED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders WHERE customer_id = ? AND idempotency_key IS NOT NULL", Long.class,
                (Object) bytes(customerId))).as("키는 바로 구매 주문에만").isEqualTo(1);
    }

    @Test
    @DisplayName("마지막 재고를 동시에 두 번 누르면 하나만 201, 나머지는 409 INSUFFICIENT_STOCK — 확보는 총량을 넘지 않는다")
    void concurrentRequestsForLastUnitCreateOne() throws Exception {
        UUID phone = sellable(1);

        List<Outcome<Integer>> outcomes = Concurrently.run(2, i -> () -> place(item(phone, 1, false)).andReturn().getResponse().getStatus());

        assertThat(outcomes).extracting(Outcome::value).containsExactlyInAnyOrder(201, 409);
        assertThat(reserved(phone)).isEqualTo(1);
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("화면에서 본 단가 · 보증가가 지금 값과 다르면 409 PRICE_CHANGED 와 지금 값 — 주문 · 확보 없음")
    void changedPriceIsConflict() throws Exception {
        UUID phone = sellable(10);

        expectError(place("""
                {"variantId":"%s","quantity":1,"warranty":true,"expectedUnitPrice":1200000,"expectedWarrantyPrice":199000}""".formatted(phone)),
                409, "PRICE_CHANGED")
                .andExpect(jsonPath("$.error.details.items[0].variantId").value(phone.toString()))
                .andExpect(jsonPath("$.error.details.items[0].unitPrice").value(1250000));

        assertThat(orderCount()).isZero();
        assertThat(reserved(phone)).isZero();
    }

    @Test
    @DisplayName("재고가 모자라면 409 INSUFFICIENT_STOCK 과 그 옵션 — 보증 포함 · 미포함 합계로 본다, 주문 · 확보 없음")
    void shortageIsConflict() throws Exception {
        UUID phone = sellable(2);

        expectError(place(item(phone, 2, false), item(phone, 1, true)), 409, "INSUFFICIENT_STOCK")
                .andExpect(jsonPath("$.error.message").value("주문 전체 수량을 확보할 수 없습니다."))
                .andExpect(jsonPath("$.error.details.variantIds[0]").value(phone.toString()));

        assertThat(orderCount()).isZero();
        assertThat(reserved(phone)).isZero();
    }

    @Test
    @DisplayName("살 수 없는 옵션이면 409 STATE_CONFLICT 와 이유 — 판매 중지 · 판매 종료 · 보증 중단")
    void unavailableOptionIsConflict() throws Exception {
        UUID paused = sellable(10);
        catalog.put(paused, copy(catalog.get(paused), "PAUSED", true));
        UUID gone = sellable(10);
        catalog.remove(gone);
        UUID warrantyStopped = sellable(10);
        catalog.put(warrantyStopped, copy(catalog.get(warrantyStopped), "ACTIVE", false));

        expectError(place(item(paused, 1, false)), 409, "STATE_CONFLICT")
                .andExpect(jsonPath("$.error.details.items[0].reason").value("PAUSED"));
        expectError(place(item(gone, 1, false)), 409, "STATE_CONFLICT")
                .andExpect(jsonPath("$.error.details.items[0].reason").value("DISCONTINUED"));
        expectError(place(item(warrantyStopped, 1, true)), 409, "STATE_CONFLICT")
                .andExpect(jsonPath("$.error.details.items[0].reason").value("WARRANTY_UNAVAILABLE"));
        assertThat(orderCount()).isZero();
    }

    /** 바로 구매는 장바구니 담기를 거치지 않는다 — 사전예약 상품을 막는 것은 주문 때의 판정(일반 판매가 아니면 HIDDEN) 하나다. */
    @Test
    void preorderModeOptionIsConflict() throws Exception {
        UUID option = sellable(10);
        CatalogOption o = catalog.get(option);
        catalog.put(option, new CatalogOption(o.optionId(), o.productId(), o.productTitle(), o.optionTitle(), o.sku(), o.price(), o.optionStatus(),
                "PREORDER", o.productStatus(), o.visible(), o.registrationCompleted(), o.warranty(), o.imageUrl()));

        expectError(place(item(option, 1, false)), 409, "STATE_CONFLICT")
                .andExpect(jsonPath("$.error.details.items[0].reason").value("HIDDEN"));

        assertThat(orderCount()).isZero();
        assertThat(reserved(option)).isZero();
    }

    @Test
    @DisplayName("요청 검증 — 옵션 둘 · 예약 토큰 동시 · 줄 없음 · 같은 줄 두 번 · 수량 범위(1~99)는 400 — 주문 · 확보 없음")
    void invalidRequestsAreRejected() throws Exception {
        UUID phone = sellable(10);
        UUID watch = sellable(10);

        expectViolation(place(item(phone, 1, false), item(watch, 1, false)), "items");
        expectViolation(perform("""
                {"source":"BUY_NOW","preorderId":"%s","items":[%s],"shipTo":%s}""".formatted(UUID.randomUUID(), item(phone, 1, false), SHIP_TO)),
                "preorderId");
        expectViolation(perform("""
                {"source":"BUY_NOW","shipTo":%s}""".formatted(SHIP_TO)), "items");
        expectViolation(place(item(phone, 1, false), item(phone, 2, false)), "items");
        expectViolation(place(item(phone, 0, false)), "items[0].quantity");
        expectViolation(place(item(phone, 100, false)), "items[0].quantity");
        assertThat(orderCount()).isZero();
        assertThat(reserved(phone)).isZero();
    }

    @Test
    @DisplayName("결제 승인 반영은 판매 확정(확보 → 판매)만 한다 — 장바구니는 같은 옵션 줄이 있어도 그대로")
    void approvalSellsStockAndKeepsCart() throws Exception {
        UUID phone = sellable(10);
        cartLine(customerId, phone, false, 3);
        cartLine(customerId, phone, true, 1);
        String order = data(place(item(phone, 2, false), item(phone, 1, true)).andExpect(status().isCreated())).get("orderId").asString();
        fixtures.forceAuthorizing(UUID.fromString(order), "p-buy-now");

        assertThat(results.settle(settlement(order, "p-buy-now", PaymentSettlement.Result.APPROVED, null)).applied()).isTrue();

        assertThat(orderStatus(order)).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(stockOf(phone)).containsExactly(10, 0, 3);
        assertThat(cart(customerId)).containsExactlyInAnyOrderEntriesOf(Map.of(phone + "/false", 3, phone + "/true", 1));
    }

    @Test
    @DisplayName("결제 거절이면 결제 대기로 돌아가 확보를 쥔 채 다시 결제할 수 있다")
    void declineReturnsToAwaitingPayment() throws Exception {
        UUID phone = sellable(10);
        String order = data(place(item(phone, 2, false)).andExpect(status().isCreated())).get("orderId").asString();
        fixtures.forceAuthorizing(UUID.fromString(order), "p-buy-now-decline");

        results.settle(settlement(order, "p-buy-now-decline", PaymentSettlement.Result.DECLINED, DeclineReason.CARD_REJECTED));

        assertThat(orderStatus(order)).isEqualTo("AWAITING_PAYMENT");
        assertThat(stockOf(phone)).containsExactly(10, 2, 0);
    }

    @Test
    @DisplayName("미결제 바로 구매 주문은 사용자가 취소한다 — 200 CANCELED · 재고 반환, 다시 불러도 반환은 한 번")
    void userCancelReleasesStockOnce() throws Exception {
        UUID phone = sellable(10);
        String order = data(place(item(phone, 3, false)).andExpect(status().isCreated())).get("orderId").asString();

        cancel(order).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELED"));
        cancel(order).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELED"));

        assertThat(reserved(phone)).isZero();
        assertThat(stockReleased(order)).isTrue();
    }

    @Test
    @DisplayName("남의 바로 구매 주문은 404, 승인 중이면 409 PAYMENT_IN_PROGRESS — 어느 쪽도 바꾸지 않는다")
    void othersOrAuthorizingOrderIsNotCanceled() throws Exception {
        UUID phone = sellable(10);
        String order = data(place(item(phone, 1, false)).andExpect(status().isCreated())).get("orderId").asString();

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", order).with(TestAuth.customer(fixtures.customer())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
        fixtures.forceAuthorizing(UUID.fromString(order), "p-buy-now-cancel");
        cancel(order).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PAYMENT_IN_PROGRESS"));

        assertThat(orderStatus(order)).isEqualTo("AUTHORIZING");
        assertThat(reserved(phone)).isEqualTo(1);
    }

    @Test
    @DisplayName("장바구니 주문과 독립 — 바로 구매가 결제 대기 장바구니 주문을 취소하지 않고, 새 장바구니 주문도 바로 구매 주문을 취소하지 않는다")
    void independentOfCartOrders() throws Exception {
        UUID phone = sellable(10);
        UUID watch = sellable(10);
        cartLine(customerId, phone, false, 1);
        String cartOrder = data(perform("""
                {"source":"CART","items":[%s],"shipTo":%s}""".formatted(item(phone, 1, false), SHIP_TO))
                .andExpect(status().isCreated())).get("orderId").asString();

        String buyNow = data(place(item(phone, 2, false)).andExpect(status().isCreated())).get("orderId").asString();
        assertThat(orderStatus(cartOrder)).as("바로 구매는 장바구니 주문을 건드리지 않는다").isEqualTo("AWAITING_PAYMENT");

        cartLine(customerId, watch, false, 1);
        String nextCartOrder = data(perform("""
                {"source":"CART","items":[%s],"shipTo":%s}""".formatted(item(watch, 1, false), SHIP_TO))
                .andExpect(status().isCreated())).get("orderId").asString();

        assertThat(orderStatus(cartOrder)).as("장바구니 주문끼리는 앞 주문을 바꾼다").isEqualTo("CANCELED");
        assertThat(orderStatus(buyNow)).as("바로 구매 주문은 그 규칙 밖").isEqualTo("AWAITING_PAYMENT");
        assertThat(orderStatus(nextCartOrder)).isEqualTo("AWAITING_PAYMENT");
        assertThat(reserved(phone)).isEqualTo(2);
        assertThat(reserved(watch)).isEqualTo(1);
    }

    /** 일반 판매 · 공개 · 판매 중 · 보증 제공 옵션. */
    private UUID sellable(int stock) {
        OrderFixtures.StockProduct product = fixtures.inStockProduct(1);
        UUID option = product.optionIds().getFirst();
        fixtures.stock(option, stock, 0, 0);
        catalog.put(option, new CatalogOption(option, product.productId(), "아이폰 17", "블랙 / 256GB", "SKU-" + option, PRICE,
                "ACTIVE", "IN_STOCK", "ACTIVE", true, true, new CatalogOption.Warranty(true, WARRANTY), null));
        return option;
    }

    private static CatalogOption copy(CatalogOption o, String optionStatus, boolean warrantyOffered) {
        return new CatalogOption(o.optionId(), o.productId(), o.productTitle(), o.optionTitle(), o.sku(), o.price(), optionStatus,
                o.saleMode(), o.productStatus(), o.visible(), o.registrationCompleted(),
                new CatalogOption.Warranty(warrantyOffered, warrantyOffered ? WARRANTY : BigDecimal.ZERO), o.imageUrl());
    }

    private PaymentSettlement settlement(String orderId, String providerOrderId, PaymentSettlement.Result result, DeclineReason reason) {
        BigDecimal amount = orderReader.findById(UUID.fromString(orderId)).orElseThrow().totalAmount().amount();
        Instant approvedAt = result == PaymentSettlement.Result.APPROVED ? Instant.parse("2026-10-16T03:00:00Z") : null;
        return new PaymentSettlement(UUID.fromString(orderId), providerOrderId, result, amount, approvedAt, reason);
    }

    private static String item(UUID option, int quantity, boolean warranty) {
        return warranty
                ? """
                  {"variantId":"%s","quantity":%d,"warranty":true,"expectedUnitPrice":1250000,"expectedWarrantyPrice":199000}"""
                .formatted(option, quantity)
                : """
                  {"variantId":"%s","quantity":%d,"expectedUnitPrice":1250000}""".formatted(option, quantity);
    }

    /** 누름 한 번 — 새 키. */
    private ResultActions place(String... items) throws Exception {
        return placeWith(UUID.randomUUID(), items);
    }

    private ResultActions placeWith(UUID key, String... items) throws Exception {
        return perform("""
                {"source":"BUY_NOW","items":[%s],"shipTo":%s}""".formatted(String.join(",", items), SHIP_TO), key);
    }

    private ResultActions perform(String body) throws Exception {
        return perform(body, UUID.randomUUID());
    }

    /** @param key null 이면 헤더를 싣지 않는다 */
    private ResultActions perform(String body, UUID key) throws Exception {
        var request = post("/api/v1/orders").with(TestAuth.customer(customerId)).contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(key == null ? request : request.header("Idempotency-Key", key.toString()));
    }

    private ResultActions cancel(String orderId) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/{id}/cancel", orderId).with(TestAuth.customer(customerId)));
    }

    private void cartLine(UUID customer, UUID option, boolean warranty, int quantity) {
        jdbcTemplate.update("""
                INSERT INTO cart_items (id, customer_id, option_id, warranty_selected, quantity, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bytes(UUID.randomUUID()), bytes(customer), bytes(option), warranty, quantity);
    }

    /** "옵션/보증" → 수량 */
    private Map<String, Integer> cart(UUID customer) {
        Map<String, Integer> lines = new HashMap<>();
        jdbcTemplate.query("SELECT option_id, warranty_selected, quantity FROM cart_items WHERE customer_id = ?",
                rs -> {
                    lines.put(com.grandis.nova.common.UuidBinary.fromBytes(rs.getBytes(1)) + "/" + rs.getBoolean(2), rs.getInt(3));
                }, (Object) bytes(customer));
        return lines;
    }

    private int reserved(UUID option) {
        return jdbcTemplate.queryForObject("SELECT stock_reserved FROM option_inventories WHERE option_id = ?", Integer.class,
                (Object) bytes(option));
    }

    /** [총량, 확보, 판매] */
    private List<Integer> stockOf(UUID option) {
        return jdbcTemplate.queryForObject("SELECT stock_total, stock_reserved, stock_sold FROM option_inventories WHERE option_id = ?",
                (rs, n) -> List.of(rs.getInt(1), rs.getInt(2), rs.getInt(3)), (Object) bytes(option));
    }

    private String orderStatus(String orderId) {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = UUID_TO_BIN(?)", String.class, orderId);
    }

    private boolean stockReleased(String orderId) {
        return jdbcTemplate.queryForObject("SELECT stock_released_at IS NOT NULL FROM orders WHERE id = UUID_TO_BIN(?)", Boolean.class, orderId);
    }

    private long orderCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders WHERE customer_id = ?", Long.class, (Object) bytes(customerId));
    }

    private static JsonNode data(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("data");
    }

    private static ResultActions expectError(ResultActions actions, int status, String code) throws Exception {
        return actions.andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
    }

    private static void expectViolation(ResultActions actions, String field) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value(field));
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
