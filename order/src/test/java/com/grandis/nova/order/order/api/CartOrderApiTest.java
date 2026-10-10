package com.grandis.nova.order.order.api;

import com.grandis.nova.common.testing.Concurrently;
import com.grandis.nova.common.testing.Concurrently.Outcome;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.cart.domain.repository.CartStore;
import com.grandis.nova.order.client.catalog.CatalogClient;
import com.grandis.nova.order.client.catalog.CatalogOption;
import com.grandis.nova.order.client.catalog.CatalogOptions;
import com.grandis.nova.order.stock.StockLedger;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static com.grandis.nova.order.support.OrderFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 장바구니 주문 생성(POST /api/v1/orders, source=CART). catalog 는 대역, 장바구니 줄 · 재고는 표에 직접 심는다.
 * 대역 구성은 CartApiTest 와 같다 — Spring 컨텍스트(와 MySQL 컨테이너)를 함께 쓴다.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
class CartOrderApiTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final BigDecimal PRICE = new BigDecimal("1250000");
    static final BigDecimal WARRANTY = new BigDecimal("199000");
    static final String SHIP_TO = """
            {"name":"홍길동","phone":"010-0000-0000","postalCode":"04524","line1":"서울시 중구 세종대로 110"}""";
    static final String OTHER_SHIP_TO = """
            {"name":"홍길동","phone":"010-0000-0000","postalCode":"48058","line1":"부산시 해운대구 센텀로 1"}""";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StockLedger stock;
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
    @DisplayName("고른 장바구니 줄로 주문 — 같은 옵션의 보증 포함 · 미포함은 한 줄로 합치고, 재고를 전량 확보하고, 기한은 10분, 장바구니는 그대로")
    void placesCartOrderMergingWarrantyLinesAndReservingStock() throws Exception {
        UUID phone = sellable(10);
        UUID watch = sellable(5);
        cartLine(phone, false, 2);
        cartLine(phone, true, 1);
        cartLine(watch, false, 4);
        UUID notChosen = sellable(5);
        cartLine(notChosen, false, 1);
        Instant before = Instant.now();

        JsonNode order = data(place(item(phone, 2, false), item(phone, 1, true), item(watch, 4, false))
                .andExpect(status().isCreated()));

        assertThat(order.get("source").asString()).isEqualTo("CART");
        assertThat(order.get("status").asString()).isEqualTo("AWAITING_PAYMENT");
        assertThat(order.get("totalAmount").decimalValue())
                .isEqualByComparingTo(PRICE.multiply(BigDecimal.valueOf(3)).add(WARRANTY).add(PRICE.multiply(BigDecimal.valueOf(4))));
        Instant due = Instant.parse(order.get("paymentDueAt").asString());
        assertThat(due).isBetween(before.plus(Duration.ofMinutes(10)).minusSeconds(1), Instant.now().plus(Duration.ofMinutes(10)));
        Map<String, JsonNode> items = byVariant(order.get("items"));
        assertThat(items).hasSize(2);
        JsonNode merged = items.get(phone.toString());
        assertThat(merged.get("quantity").asInt()).isEqualTo(3);
        assertThat(merged.get("warrantyQuantity").asInt()).isEqualTo(1);
        assertThat(merged.get("warrantyUnitPrice").decimalValue()).isEqualByComparingTo(WARRANTY);
        assertThat(merged.get("lineAmount").decimalValue()).isEqualByComparingTo(PRICE.multiply(BigDecimal.valueOf(3)).add(WARRANTY));
        assertThat(items.get(watch.toString()).get("warrantyQuantity").asInt()).isZero();
        assertThat(items.get(watch.toString()).get("warrantyUnitPrice").decimalValue()).isEqualByComparingTo("0");

        assertThat(reserved(phone)).isEqualTo(3);
        assertThat(reserved(watch)).isEqualTo(4);
        assertThat(reserved(notChosen)).as("고르지 않은 줄은 확보하지 않는다").isZero();
        assertThat(cartQuantities()).as("장바구니는 결제 성공 때 뺀다").hasSize(4);

        JsonNode detail = data(mockMvc.perform(get("/api/v1/orders/{id}", order.get("orderId").asString())
                .with(TestAuth.customer(customerId))).andExpect(status().isOk()));
        assertThat(byVariant(detail.get("items")).get(phone.toString()).get("warrantyQuantity").asInt()).isEqualTo(1);
        assertThat(detail.get("paymentDueAt").asString()).isEqualTo(order.get("paymentDueAt").asString());
    }

    @Test
    @DisplayName("구성 · 단가 · 보증가 · 배송지가 모두 같으면 새 주문 · 새 확보 없이 기존 주문(200, reused) — 승인 중이어도")
    void identicalRequestReusesOpenOrder() throws Exception {
        UUID phone = sellable(10);
        cartLine(phone, false, 2);

        JsonNode first = data(place(item(phone, 2, false)).andExpect(status().isCreated()));
        assertThat(first.get("reused").asBoolean()).isFalse();
        JsonNode again = data(place(item(phone, 2, false)).andExpect(status().isOk()));

        assertThat(again.get("orderId").asString()).isEqualTo(first.get("orderId").asString());
        assertThat(again.get("reused").asBoolean()).isTrue();
        assertThat(reserved(phone)).isEqualTo(2);

        jdbcTemplate.update("UPDATE orders SET status = 'AUTHORIZING', authorizing_provider_order_id = 'p-1' WHERE order_token = ?",
                first.get("orderId").asString());
        assertThat(data(place(item(phone, 2, false)).andExpect(status().isOk())).get("orderId").asString())
                .as("승인 중도 열린 주문이다").isEqualTo(first.get("orderId").asString());
    }

    @Test
    @DisplayName("같은 구성인데 배송지 · 단가가 다르면 새 주문을 만들고 결제 대기인 앞 주문은 취소 · 재고 반환 — 결제 대기는 늘 하나")
    void changedValuesReplaceUnpaidOrder() throws Exception {
        UUID phone = sellable(10);
        cartLine(phone, false, 2);
        String first = data(place(item(phone, 2, false)).andExpect(status().isCreated())).get("orderId").asString();

        String moved = data(perform("""
                {"source":"CART","items":[%s],"shipTo":%s}""".formatted(item(phone, 2, false), OTHER_SHIP_TO))
                .andExpect(status().isCreated())).get("orderId").asString();

        assertThat(moved).isNotEqualTo(first);
        assertThat(orderStatus(first)).isEqualTo("CANCELED");
        assertThat(stockReleased(first)).as("반환 표식").isTrue();
        assertThat(reserved(phone)).as("앞 주문의 확보는 돌아갔다").isEqualTo(2);
        assertThat(lastEventReason(first)).isEqualTo("REPLACED_BY_NEW_ORDER");

        // 이번엔 배송지는 앞 주문(moved)과 같게 두고 단가만 바꾼다 — 가격 축만 다르다
        catalog.put(phone, copyPrice(catalog.get(phone), new BigDecimal("1000000")));
        String cheaper = data(perform("""
                {"source":"CART","items":[{"variantId":"%s","quantity":2,"expectedUnitPrice":1000000}],"shipTo":%s}"""
                .formatted(phone, OTHER_SHIP_TO)).andExpect(status().isCreated())).get("orderId").asString();
        assertThat(orderStatus(moved)).isEqualTo("CANCELED");
        assertThat(orderTotal(cheaper)).isEqualByComparingTo("2000000");
        assertThat(reserved(phone)).isEqualTo(2);
        assertThat(openOrderCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("보증가만 달라도 새 주문 · 앞 주문 취소 — 보증 줄의 값도 사용자가 확인한 그대로여야 한다")
    void changedWarrantyPriceReplacesUnpaidOrder() throws Exception {
        UUID phone = sellable(10);
        cartLine(phone, true, 1);
        String first = data(place(item(phone, 1, true)).andExpect(status().isCreated())).get("orderId").asString();

        CatalogOption current = catalog.get(phone);
        catalog.put(phone, new CatalogOption(current.optionId(), current.productId(), current.productTitle(), current.optionTitle(), current.sku(),
                current.price(), current.optionStatus(), current.saleMode(), current.productStatus(), current.visible(),
                current.registrationCompleted(), new CatalogOption.Warranty(true, new BigDecimal("149000")), current.imageUrl()));
        String second = data(place("""
                {"variantId":"%s","quantity":1,"warranty":true,"expectedUnitPrice":1250000,"expectedWarrantyPrice":149000}""".formatted(phone))
                .andExpect(status().isCreated())).get("orderId").asString();

        assertThat(second).isNotEqualTo(first);
        assertThat(orderStatus(first)).isEqualTo("CANCELED");
        assertThat(orderTotal(second)).isEqualByComparingTo("1399000");
        assertThat(reserved(phone)).isEqualTo(1);
    }

    /**
     * 열린 주문은 잠그지 않고 읽는다 — 읽은 뒤 잠그기 전에 결제 시작(주문 행만 잠근다)이 앞 주문을 승인 중으로 바꾸면, 잠근 뒤 다시 보고 409 다.
     * 결정적으로 본다: 다른 트랜잭션이 앞 주문을 승인 중으로 바꾼 채 커밋하지 않고, 값이 다른 재주문이 그 행 잠금에서 기다리는 것을 확인한 뒤 커밋한다.
     */
    @Test
    void orderTurningAuthorizingWhileReplacingIsConflict() throws Exception {
        AtomicReference<String> lastReplaceBody = new AtomicReference<>();
        UUID phone = sellable(10);
        cartLine(phone, false, 1);
        String first = data(place(item(phone, 1, false)).andExpect(status().isCreated())).get("orderId").asString();
        CountDownLatch authorized = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CompletableFuture<Void> payment = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            assertThat(jdbcTemplate.update("""
                    UPDATE orders SET status = 'AUTHORIZING', authorizing_provider_order_id = 'p-race'
                     WHERE order_token = ? AND status = 'AWAITING_PAYMENT'""", first)).isEqualTo(1);
            authorized.countDown();
            await(commit);
        }));
        await(authorized);
        CompletableFuture<Integer> replace = CompletableFuture.supplyAsync(() -> {
            try {
                var response = perform("""
                        {"source":"CART","items":[%s],"shipTo":%s}""".formatted(item(phone, 1, false), OTHER_SHIP_TO))
                        .andReturn().getResponse();
                lastReplaceBody.set(response.getContentAsString());
                return response.getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        waitForQuery("SELECT status FROM orders WHERE id = %FOR UPDATE");
        commit.countDown();
        payment.get(20, TimeUnit.SECONDS);

        assertThat(replace.get(20, TimeUnit.SECONDS)).isEqualTo(409);
        assertThat(lastReplaceBody.get()).contains("PAYMENT_IN_PROGRESS").contains(first);
        assertThat(orderCount()).isEqualTo(1);
        assertThat(orderStatus(first)).isEqualTo("AUTHORIZING");
        assertThat(reserved(phone)).isEqualTo(1);
    }

    @Test
    @DisplayName("결제 안 된 장바구니 주문은 회원당 하나 — 구성이 달라도(보증 수량 · 다른 상품) 새 주문이 앞 주문을 취소 · 반환한다")
    void newOrderCancelsUnpaidOrderOfAnyComposition() throws Exception {
        UUID phone = sellable(10);
        UUID watch = sellable(10);
        cartLine(phone, false, 2);
        cartLine(watch, false, 1);
        String plain = data(place(item(phone, 2, false)).andExpect(status().isCreated())).get("orderId").asString();

        jdbcTemplate.update("UPDATE cart_items SET warranty_selected = 1 WHERE customer_id = ? AND option_id = ?", bytes(customerId), bytes(phone));
        String withWarranty = data(place(item(phone, 2, true)).andExpect(status().isCreated())).get("orderId").asString();
        assertThat(withWarranty).isNotEqualTo(plain);
        assertThat(orderStatus(plain)).isEqualTo("CANCELED");
        assertThat(reserved(phone)).isEqualTo(2);

        String other = data(place(item(watch, 1, false)).andExpect(status().isCreated())).get("orderId").asString();
        assertThat(orderStatus(withWarranty)).as("다른 상품의 새 주문도 앞 주문을 바꾼다").isEqualTo("CANCELED");
        assertThat(reserved(phone)).isZero();
        assertThat(reserved(watch)).isEqualTo(1);
        assertThat(openOrderCount()).isEqualTo(1);
        assertThat(data(mockMvc.perform(get("/api/v1/orders/{id}", other).with(TestAuth.customer(customerId)))).get("status").asString())
                .isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    @DisplayName("결제 진행 중(승인 중)인 앞 주문이 있으면 구성이 달라도 새 장바구니 주문은 409 PAYMENT_IN_PROGRESS 와 막는 주문 — 그 옆에 만들지 않는다")
    void authorizingOrderBlocksNewOrderOfAnyComposition() throws Exception {
        UUID phone = sellable(10);
        UUID watch = sellable(10);
        cartLine(phone, false, 1);
        cartLine(watch, false, 1);
        String first = data(place(item(phone, 1, false)).andExpect(status().isCreated())).get("orderId").asString();
        jdbcTemplate.update("UPDATE orders SET status = 'AUTHORIZING', authorizing_provider_order_id = 'p-3' WHERE order_token = ?", first);

        expectBlockedBy(place(item(watch, 1, false)), first);

        assertThat(orderCount()).isEqualTo(1);
        assertThat(reserved(watch)).isZero();
    }

    @Test
    @DisplayName("같은 구성의 승인 중 주문이 있는데 값이 다르면 409 PAYMENT_IN_PROGRESS — 결제가 진행 중인 주문은 바꾸지 않는다")
    void authorizingOrderIsNotReplaced() throws Exception {
        UUID phone = sellable(10);
        cartLine(phone, false, 1);
        String first = data(place(item(phone, 1, false)).andExpect(status().isCreated())).get("orderId").asString();
        jdbcTemplate.update("UPDATE orders SET status = 'AUTHORIZING', authorizing_provider_order_id = 'p-2' WHERE order_token = ?", first);

        expectBlockedBy(perform("""
                {"source":"CART","items":[%s],"shipTo":%s}""".formatted(item(phone, 1, false), OTHER_SHIP_TO)), first);

        assertThat(orderStatus(first)).isEqualTo("AUTHORIZING");
        assertThat(reserved(phone)).isEqualTo(1);
    }

    @Test
    @DisplayName("기한이 지난 결제 대기 주문은 다시 쓰지 않고, 아직 확보를 쥐고 있으니 새 주문이 취소 · 반환한다 — 같은 구성이어도")
    void expiredOrderIsNotReusedButReplaced() throws Exception {
        UUID phone = sellable(10);
        cartLine(phone, false, 1);
        String first = data(place(item(phone, 1, false)).andExpect(status().isCreated())).get("orderId").asString();
        expire(first);

        String second = data(place(item(phone, 1, false)).andExpect(status().isCreated())).get("orderId").asString();

        assertThat(second).isNotEqualTo(first);
        assertThat(orderStatus(first)).isEqualTo("CANCELED");
        assertThat(reserved(phone)).isEqualTo(1);
        assertThat(openOrderCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("기한이 지난(결과를 못 받고 멈춘) 승인 중 주문도 결제가 진행 중이다 — 새 장바구니 주문은 409 와 막는 주문(돈이 나갔을 수 있다)")
    void expiredAuthorizingOrderStillBlocks() throws Exception {
        UUID phone = sellable(10);
        cartLine(phone, false, 1);
        String first = data(place(item(phone, 1, false)).andExpect(status().isCreated())).get("orderId").asString();
        jdbcTemplate.update("UPDATE orders SET status = 'AUTHORIZING', authorizing_provider_order_id = 'p-4' WHERE order_token = ?", first);
        expire(first);

        expectBlockedBy(perform("""
                {"source":"CART","items":[%s],"shipTo":%s}""".formatted(item(phone, 1, false), OTHER_SHIP_TO)), first);
        assertThat(orderCount()).isEqualTo(1);
        assertThat(orderStatus(first)).isEqualTo("AUTHORIZING");
        assertThat(reserved(phone)).isEqualTo(1);
    }

    @Test
    @DisplayName("보증가 · 금액 칸 말고 보증 수량만 달라도 다른 구성 — 2개 중 보증 1 뒤에 보증 2 는 새 주문")
    void warrantyQuantityIsPartOfComposition() throws Exception {
        UUID phone = sellable(10);
        cartLine(phone, false, 1);
        cartLine(phone, true, 1);
        String first = data(place(item(phone, 1, false), item(phone, 1, true)).andExpect(status().isCreated())).get("orderId").asString();

        jdbcTemplate.update("DELETE FROM cart_items WHERE customer_id = ? AND warranty_selected = 0", (Object) bytes(customerId));
        jdbcTemplate.update("UPDATE cart_items SET quantity = 2 WHERE customer_id = ?", (Object) bytes(customerId));
        String second = data(place(item(phone, 2, true)).andExpect(status().isCreated())).get("orderId").asString();

        assertThat(second).isNotEqualTo(first);
        assertThat(orderStatus(first)).isEqualTo("CANCELED");
        assertThat(orderTotal(second)).isEqualByComparingTo(PRICE.multiply(BigDecimal.valueOf(2)).add(WARRANTY.multiply(BigDecimal.valueOf(2))));
    }

    @Test
    @DisplayName("바꾸는 도중 새 주문의 재고가 모자라면 409 INSUFFICIENT_STOCK — 앞 주문의 취소 · 반환도 되돌아가 그대로 남는다")
    void shortageWhileReplacingKeepsPreviousOrder() throws Exception {
        UUID phone = sellable(4);
        cartLine(phone, false, 1);
        String first = data(place(item(phone, 1, false)).andExpect(status().isCreated())).get("orderId").asString();
        jdbcTemplate.update("UPDATE cart_items SET quantity = 5 WHERE customer_id = ?", (Object) bytes(customerId));

        expectError(place(item(phone, 5, false)), 409, "INSUFFICIENT_STOCK");

        assertThat(orderStatus(first)).isEqualTo("AWAITING_PAYMENT");
        assertThat(stockReleased(first)).isFalse();
        assertThat(reserved(phone)).isEqualTo(1);
        assertThat(orderCount()).isEqualTo(1);
    }

    /**
     * 앞 주문의 반환과 새 주문의 확보는 옵션별 증감 하나로 합쳐 잠금 순서대로 한다 — 결정적으로 본다.
     * 앞 주문은 뒤 옵션(high)을, 재주문은 앞 옵션(low)을 잡는다. 다른 트랜잭션이 정상 순서로 low → high 를 확보하는 사이, low 에서 기다리는
     * 재주문은 high 를 아직 쥐지 않아 교착이 없다. 반환(high)을 먼저 따로 하면 재주문이 high 를 쥔 채 low 를 기다려 교착(1213)이다.
     */
    @Test
    void replacementAppliesReleaseAndReserveInOneLockOrder() throws Exception {
        List<UUID> options = new ArrayList<>(List.of(sellable(10), sellable(10)));
        options.sort((a, b) -> Arrays.compareUnsigned(bytes(a), bytes(b)));
        UUID low = options.get(0);
        UUID high = options.get(1);
        cartLine(high, false, 1);
        cartLine(low, false, 1);
        String first = data(place(item(high, 1, false)).andExpect(status().isCreated())).get("orderId").asString();
        CountDownLatch reservedLow = new CountDownLatch(1);
        CountDownLatch reorderWaiting = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CompletableFuture<Void> other = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            stock.reserve(Map.of(low, 1));
            reservedLow.countDown();
            await(reorderWaiting);
            stock.reserve(Map.of(high, 1));
        }));
        await(reservedLow);
        CompletableFuture<Integer> reorder = CompletableFuture.supplyAsync(() -> {
            try {
                return place(item(low, 1, false)).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        waitForQuery("UPDATE option_inventories SET stock_reserved = stock_reserved %");
        reorderWaiting.countDown();

        assertThat(other).as("상관없는 확보는 교착에 끌려들지 않는다").succeedsWithin(Duration.ofSeconds(20));
        assertThat(reorder.get(20, TimeUnit.SECONDS)).isEqualTo(201);
        assertThat(orderStatus(first)).isEqualTo("CANCELED");
        assertThat(reserved(low)).isEqualTo(2);
        assertThat(reserved(high)).isEqualTo(1);
    }

    @Test
    @DisplayName("고른 줄이 지금 장바구니와 다르면 409 CART_CHANGED — 수량이 다름 · 없는 줄 · 보증 여부가 다름")
    void selectionMustMatchCurrentCart() throws Exception {
        UUID phone = sellable(10);
        cartLine(phone, false, 2);

        expectError(place(item(phone, 3, false)), 409, "CART_CHANGED");
        expectError(place(item(phone, 2, true)), 409, "CART_CHANGED");
        expectError(place(item(sellable(10), 1, false)), 409, "CART_CHANGED");
        assertThat(reserved(phone)).isZero();
        assertThat(orderCount()).isZero();
    }

    @Test
    @DisplayName("화면에서 본 단가 · 보증가가 지금 값과 다르면 409 PRICE_CHANGED 와 지금 값")
    void changedPriceIsReportedWithCurrentPrice() throws Exception {
        UUID phone = sellable(10);
        cartLine(phone, true, 1);

        expectError(place(String.format("""
                {"variantId":"%s","quantity":1,"warranty":true,"expectedUnitPrice":1200000,"expectedWarrantyPrice":199000}""", phone)),
                409, "PRICE_CHANGED")
                .andExpect(jsonPath("$.error.details.items[0].variantId").value(phone.toString()))
                .andExpect(jsonPath("$.error.details.items[0].unitPrice").value(1250000))
                .andExpect(jsonPath("$.error.details.items[0].warrantyPrice").value(199000));
        expectError(place(String.format("""
                {"variantId":"%s","quantity":1,"warranty":true,"expectedUnitPrice":1250000,"expectedWarrantyPrice":99000}""", phone)),
                409, "PRICE_CHANGED");
        assertThat(orderCount()).isZero();
    }

    @Test
    @DisplayName("살 수 없는 줄이 있으면 409 STATE_CONFLICT 와 줄마다 이유 — 판매 중지 · 숨김 · 판매 종료 · 보증 중단")
    void unavailableLinesAreRejectedWithReasons() throws Exception {
        UUID paused = sellable(10);
        UUID hidden = sellable(10);
        UUID gone = sellable(10);
        UUID warrantyStopped = sellable(10);
        for (UUID option : List.of(paused, hidden, gone)) {
            cartLine(option, false, 1);
        }
        cartLine(warrantyStopped, true, 1);
        catalog.put(paused, copy(catalog.get(paused), "ACTIVE", "PAUSED", true, true));
        catalog.put(hidden, copy(catalog.get(hidden), "ACTIVE", "ACTIVE", false, true));
        catalog.remove(gone);
        catalog.put(warrantyStopped, copy(catalog.get(warrantyStopped), "ACTIVE", "ACTIVE", true, false));

        JsonNode error = JSON.readTree(expectError(place(item(paused, 1, false), item(hidden, 1, false), item(gone, 1, false),
                item(warrantyStopped, 1, true)), 409, "STATE_CONFLICT").andReturn().getResponse().getContentAsString());

        Map<String, String> reasons = new HashMap<>();
        error.get("error").get("details").get("items").forEach(i -> reasons.put(i.get("variantId").asString(), i.get("reason").asString()));
        assertThat(reasons).containsExactlyInAnyOrderEntriesOf(Map.of(paused.toString(), "PAUSED", hidden.toString(), "HIDDEN",
                gone.toString(), "DISCONTINUED", warrantyStopped.toString(), "WARRANTY_UNAVAILABLE"));
        assertThat(orderCount()).isZero();
    }

    @Test
    @DisplayName("하나라도 재고가 모자라면 409 INSUFFICIENT_STOCK — 주문을 만들지 않고, 먼저 확보한 옵션도 되돌린다(재고 행 없음도 부족)")
    void shortageOfAnyOptionCreatesNothing() throws Exception {
        UUID enough = sellable(10);
        UUID scarce = sellable(1);
        UUID noRow = sellable(-1);
        cartLine(enough, false, 3);
        cartLine(scarce, false, 2);
        cartLine(noRow, false, 1);

        expectError(place(item(enough, 3, false), item(scarce, 2, false), item(noRow, 1, false)), 409, "INSUFFICIENT_STOCK")
                .andExpect(jsonPath("$.error.details.variantIds.length()").value(2));

        assertThat(reserved(enough)).as("커밋 경계에서 되돌아갔다").isZero();
        assertThat(reserved(scarce)).isZero();
        assertThat(orderCount()).isZero();
    }

    @Test
    @DisplayName("요청 검증 — 줄 없음 · 같은 줄 두 번 · 보증 줄의 보증가 없음 · 수량 0 · 51줄 · 다른 출처의 칸은 400, 바로 구매는 아직 400")
    void invalidRequestsAreRejected() throws Exception {
        UUID phone = sellable(10);
        cartLine(phone, false, 1);

        expectViolation(perform("""
                {"source":"CART","shipTo":%s}""".formatted(SHIP_TO)), "items");
        expectViolation(place(item(phone, 1, false), item(phone, 1, false)), "items");
        expectViolation(place(String.format("""
                {"variantId":"%s","quantity":1,"warranty":true,"expectedUnitPrice":1250000}""", phone)), "items.expectedWarrantyPrice");
        expectViolation(place(item(phone, 0, false)), "items[0].quantity");
        expectViolation(place(IntStream.range(0, 51).mapToObj(i -> item(UUID.randomUUID(), 1, false)).toArray(String[]::new)),
                "items");
        expectViolation(perform("""
                {"source":"BUY_NOW","items":[%s],"shipTo":%s}""".formatted(item(phone, 1, false), SHIP_TO)), "source");
        expectViolation(perform("""
                {"source":"CART","preorderId":"%s","items":[%s],"shipTo":%s}""".formatted(UUID.randomUUID(), item(phone, 1, false), SHIP_TO)),
                "preorderId");
        expectViolation(perform("""
                {"source":"PREORDER","preorderId":"%s","items":[%s],"shipTo":%s}""".formatted(UUID.randomUUID(), item(phone, 1, false), SHIP_TO)),
                "items");
        assertThat(orderCount()).isZero();
    }

    @Test
    @DisplayName("두 회원이 마지막 재고를 동시에 주문하면 하나만 만들어지고 나머지는 409 — 확보는 총량을 넘지 않는다")
    void concurrentOrdersForLastUnitCreateOne() throws Exception {
        UUID phone = sellable(1);
        UUID other = fixtures.customer();
        cartLine(phone, false, 1);
        cartLineOf(other, phone, false, 1);

        List<Outcome<Integer>> outcomes = Concurrently.run(2, i -> () -> mockMvc.perform(post("/api/v1/orders")
                        .with(TestAuth.customer(i == 0 ? customerId : other)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"source":"CART","items":[%s],"shipTo":%s}""".formatted(item(phone, 1, false), SHIP_TO)))
                .andReturn().getResponse().getStatus());

        assertThat(outcomes).extracting(Outcome::value).containsExactlyInAnyOrder(201, 409);
        assertThat(reserved(phone)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 회원이 같은 구성을 동시에 두 번 보내면 주문 하나 · 확보 한 번 — 뒤의 요청은 장바구니 잠금 뒤에서 앞의 주문을 돌려받는다")
    void concurrentDoubleSubmitCreatesOneOrder() throws Exception {
        UUID phone = sellable(10);
        cartLine(phone, false, 2);

        List<Outcome<String>> outcomes = Concurrently.run(2, i -> () -> data(place(item(phone, 2, false))).get("orderId").asString());

        assertThat(outcomes).extracting(Outcome::value).doesNotContainNull().hasSize(2);
        assertThat(outcomes.get(0).value()).isEqualTo(outcomes.get(1).value());
        assertThat(orderCount()).isEqualTo(1);
        assertThat(reserved(phone)).isEqualTo(2);
    }

    @Test
    @DisplayName("주문 금액이 칸(12자리)을 넘으면 400 — 주문 · 확보 없음. 꼭 999,999,999,999 원은 된다")
    void totalOverLimitIsRejected() throws Exception {
        UUID exact = sellable(5);
        catalog.put(exact, copyPrice(catalog.get(exact), new BigDecimal("999999999999")));
        cartLine(exact, false, 1);
        place("""
                {"variantId":"%s","quantity":1,"expectedUnitPrice":999999999999}""".formatted(exact)).andExpect(status().isCreated());

        UUID costly = sellable(100);
        catalog.put(costly, copyPrice(catalog.get(costly), new BigDecimal("999999999999")));
        cartLine(costly, false, 2);

        expectViolation(place("""
                {"variantId":"%s","quantity":2,"expectedUnitPrice":999999999999}""".formatted(costly)), "items");
        assertThat(reserved(costly)).isZero();
    }

    /**
     * 여러 옵션의 확보는 요청의 줄 순서가 아니라 잠금 순서(option_id 바이트 오름차순)로 한다 — 결정적으로 본다.
     * 다른 트랜잭션이 앞 옵션(A)을 확보한 채 열려 있을 때, 줄을 [B, A] 로 보낸 주문은 A 에서 기다린다(B 를 쥐지 않는다).
     * 그래서 그 트랜잭션이 이어서 B 를 확보해도 교착하지 않는다. 줄 순서대로 잠그면 주문이 B 를 쥔 채 A 를 기다려 교착(1213)이다.
     */
    @Test
    void reservationLocksOptionsInStorageOrderRegardlessOfRequestOrder() throws Exception {
        List<UUID> options = new ArrayList<>(List.of(sellable(10), sellable(10)));
        options.sort((a, b) -> Arrays.compareUnsigned(bytes(a), bytes(b)));
        UUID first = options.get(0);
        UUID second = options.get(1);
        cartLine(second, false, 1);
        cartLine(first, false, 1);
        CountDownLatch reservedFirst = new CountDownLatch(1);
        CountDownLatch requestWaiting = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            stock.reserve(Map.of(first, 1));
            reservedFirst.countDown();
            await(requestWaiting);
            stock.reserve(Map.of(second, 1));
        }));
        await(reservedFirst);
        CompletableFuture<Integer> request = CompletableFuture.supplyAsync(() -> {
            try {
                return place(item(second, 1, false), item(first, 1, false)).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        waitForLockWait();
        requestWaiting.countDown();

        assertThat(holder).as("교착 없이 커밋").succeedsWithin(Duration.ofSeconds(20));
        assertThat(request.get(20, TimeUnit.SECONDS)).isEqualTo(201);
        assertThat(reserved(first)).isEqualTo(2);
        assertThat(reserved(second)).isEqualTo(2);
    }

    /** 일반 판매 · 공개 · 판매 중 · 보증 제공 옵션. stock 이 음수면 재고 행을 만들지 않는다. */
    private UUID sellable(int stock) {
        OrderFixtures.StockProduct product = fixtures.inStockProduct(1);
        UUID option = product.optionIds().getFirst();
        if (stock >= 0) {
            fixtures.stock(option, stock, 0, 0);
        }
        catalog.put(option, new CatalogOption(option, product.productId(), "아이폰 17", "블랙 / 256GB", "SKU-" + option, PRICE,
                "ACTIVE", "IN_STOCK", "ACTIVE", true, true, new CatalogOption.Warranty(true, WARRANTY), null));
        return option;
    }

    private static CatalogOption copy(CatalogOption o, String productStatus, String optionStatus, boolean visible, boolean warrantyOffered) {
        return new CatalogOption(o.optionId(), o.productId(), o.productTitle(), o.optionTitle(), o.sku(), o.price(), optionStatus,
                o.saleMode(), productStatus, visible, true,
                new CatalogOption.Warranty(warrantyOffered, warrantyOffered ? WARRANTY : BigDecimal.ZERO), o.imageUrl());
    }

    private static void expectBlockedBy(ResultActions actions, String orderToken) throws Exception {
        actions.andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_IN_PROGRESS"))
                .andExpect(jsonPath("$.error.details.orderId").value(orderToken))
                .andExpect(jsonPath("$.error.details.status").value("AUTHORIZING"));
    }

    /** 저장은 UTC 다 — JVM 시간대(KST)로 바인딩되는 Timestamp 를 쓰지 않고 DB 의 UTC 로 기한을 지나게 한다. */
    private void expire(String orderToken) {
        jdbcTemplate.update("UPDATE orders SET payment_due_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE order_token = ?", orderToken);
    }

    private static CatalogOption copyPrice(CatalogOption o, BigDecimal price) {
        return new CatalogOption(o.optionId(), o.productId(), o.productTitle(), o.optionTitle(), o.sku(), price, o.optionStatus(),
                o.saleMode(), o.productStatus(), o.visible(), o.registrationCompleted(), o.warranty(), o.imageUrl());
    }

    /** 그 모양의 문장이 실행 중일 때까지(최대 10초) — 같은 계정의 연결이 보이는 PROCESSLIST 로. 잠금이 없으면 바로 끝나는 문장만 쓴다. */
    private void waitForQuery(String like) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Long running = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE COMMAND = 'Query' AND INFO LIKE ?", Long.class, like);
            if (running != null && running > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("그 문장이 기다리지 않았다: " + like);
    }

    /**
     * 주문의 확보 UPDATE 가 행 잠금에서 멈출 때까지(최대 10초). 시험 계정은 잠금 대기 표(performance_schema)를 못 읽어(SELECT 거부),
     * 같은 계정의 연결은 볼 수 있는 PROCESSLIST 에서 "실행 중인 확보 UPDATE" 를 센다 — 잠금이 없으면 마이크로초에 끝나는 문장이라
     * 보인다면 기다리는 중이다. 계기가 0 을 내면 시험이 시간 초과로 실패한다.
     */
    private void waitForLockWait() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Long waits = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.PROCESSLIST
                     WHERE COMMAND = 'Query' AND INFO LIKE 'UPDATE option_inventories SET stock_reserved = stock_reserved +%'""", Long.class);
            if (waits != null && waits > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("잠금 대기가 생기지 않았다");
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

    private String orderStatus(String orderToken) {
        return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE order_token = ?", String.class, orderToken);
    }

    private boolean stockReleased(String orderToken) {
        return jdbcTemplate.queryForObject("SELECT stock_released_at IS NOT NULL FROM orders WHERE order_token = ?", Boolean.class, orderToken);
    }

    private BigDecimal orderTotal(String orderToken) {
        return jdbcTemplate.queryForObject("SELECT total_amount FROM orders WHERE order_token = ?", BigDecimal.class, orderToken);
    }

    private String lastEventReason(String orderToken) {
        return jdbcTemplate.queryForObject("""
                SELECT e.reason FROM order_events e JOIN orders o ON o.id = e.order_id
                 WHERE o.order_token = ? ORDER BY e.event_sequence DESC LIMIT 1""", String.class, orderToken);
    }

    private long openOrderCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders WHERE customer_id = ? AND status = 'AWAITING_PAYMENT'", Long.class,
                (Object) bytes(customerId));
    }

    private void cartLine(UUID option, boolean warranty, int quantity) {
        cartLineOf(customerId, option, warranty, quantity);
    }

    private void cartLineOf(UUID customer, UUID option, boolean warranty, int quantity) {
        jdbcTemplate.update("""
                INSERT INTO cart_items (id, customer_id, option_id, warranty_selected, quantity, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, bytes(UUID.randomUUID()), bytes(customer), bytes(option), warranty, quantity);
    }

    private static String item(UUID option, int quantity, boolean warranty) {
        return warranty
                ? """
                  {"variantId":"%s","quantity":%d,"warranty":true,"expectedUnitPrice":1250000,"expectedWarrantyPrice":199000}"""
                .formatted(option, quantity)
                : """
                  {"variantId":"%s","quantity":%d,"expectedUnitPrice":1250000}""".formatted(option, quantity);
    }

    private ResultActions place(String... items) throws Exception {
        return perform("""
                {"source":"CART","items":[%s],"shipTo":%s}""".formatted(String.join(",", items), SHIP_TO));
    }

    private ResultActions perform(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/orders").with(TestAuth.customer(customerId))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int reserved(UUID option) {
        List<Integer> rows = jdbcTemplate.queryForList("SELECT stock_reserved FROM option_inventories WHERE option_id = ?", Integer.class,
                (Object) bytes(option));
        return rows.isEmpty() ? 0 : rows.getFirst();
    }

    private long orderCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders WHERE customer_id = ?", Long.class, (Object) bytes(customerId));
    }

    private List<Integer> cartQuantities() {
        return jdbcTemplate.queryForList("SELECT quantity FROM cart_items WHERE customer_id = ?", Integer.class, (Object) bytes(customerId));
    }

    private static Map<String, JsonNode> byVariant(JsonNode items) {
        Map<String, JsonNode> byVariant = new HashMap<>();
        items.forEach(item -> byVariant.put(item.get("optionId").asString(), item));
        return byVariant;
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
}
