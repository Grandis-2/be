package com.grandis.nova.catalog.review;

import com.grandis.nova.catalog.integration.MemberClient;
import com.grandis.nova.catalog.integration.OrderClient;
import com.grandis.nova.catalog.support.AccessTokens;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.web.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 리뷰를 쓸 수 있는 내 주문상품. order 내부 API 는 계약(contracts/order-internal.md)대로 만든 대역이다 — 실제 HTTP 와 토큰 릴레이는
 * {@link ReviewInternalCallsTest} 가 본다.
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
@DisplayName("리뷰를 쓸 수 있는 내 주문상품 — 배송 완료된 구매 주문상품 중 아직 리뷰를 안 쓴 것")
class WritableReviewApiTest {

    private static final String PATH = "/api/v1/reviews/writable";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean OrderClient orders;
    @MockitoBean MemberClient members;

    ShopFixtures fixtures;
    UUID customerId;
    UUID productId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        customerId = UUID.randomUUID();
        productId = fixtures.product("IN_STOCK", "ACTIVE");
        given(members.getMe()).willReturn(ApiResponse.ok(new MemberClient.Customer(customerId, "철수랑", "김철수")));
    }

    @Test
    @DisplayName("order 가 준 순서대로, 주문 당시 이름 · 사진 · 수량 · 금액 · 주문 시각을 싣는다")
    void listsDeliveredItemsInOrderOfTheReply() throws Exception {
        OrderClient.DeliveredItem newer = item(productId, "2026-10-06T06:00:00Z");
        OrderClient.DeliveredItem older = item(productId, "2026-10-01T06:00:00Z");
        stubDelivered(newer, older);

        mockMvc.perform(get(PATH).with(AccessTokens.customer(customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[*].orderItemId", contains(newer.orderItemId().toString(), older.orderItemId().toString())))
                .andExpect(jsonPath("$.data.items[0].orderId").value(newer.orderId().toString()))
                .andExpect(jsonPath("$.data.items[0].productId").value(productId.toString()))
                .andExpect(jsonPath("$.data.items[0].optionId").value(newer.optionId().toString()))
                .andExpect(jsonPath("$.data.items[0].productTitle").value("주문 때 이름"))
                .andExpect(jsonPath("$.data.items[0].optionTitle").value("블랙 / 256GB"))
                .andExpect(jsonPath("$.data.items[0].imageUrl").value("https://img.test/a.webp"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(1))
                .andExpect(jsonPath("$.data.items[0].lineAmount").value(1250000))
                .andExpect(jsonPath("$.data.items[0].orderedAt").value("2026-10-06T06:00:00Z"));
    }

    /* 쓰면 빠지고, 지우면 다시 나온다 — 캐시하지 않아 바로 따라간다. */
    @Test
    @DisplayName("리뷰를 쓰면 목록에서 바로 빠지고, 지우면 다시 나온다")
    void writingAReviewRemovesTheItemAndDeletingBringsItBack() throws Exception {
        OrderClient.DeliveredItem first = item(productId, "2026-10-06T06:00:00Z");
        OrderClient.DeliveredItem second = item(productId, "2026-10-05T06:00:00Z");
        stubDelivered(first, second);
        given(orders.getOrderItem(eq(first.orderItemId()))).willReturn(ApiResponse.ok(new OrderClient.OrderItem(first.orderItemId(),
                first.orderId(), productId, first.optionId(), first.optionTitle(), "DELIVERED", "CART")));

        String written = mockMvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderItemId\":\"%s\",\"rating\":5,\"body\":\"좋아요\"}".formatted(first.orderItemId()))
                        .with(AccessTokens.customer(customerId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(get(PATH).with(AccessTokens.customer(customerId)))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[*].orderItemId", contains(second.orderItemId().toString())));

        String reviewId = com.jayway.jsonpath.JsonPath.read(written, "$.data.reviewId");
        mockMvc.perform(delete("/api/v1/reviews/{id}", reviewId).with(AccessTokens.customer(customerId)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(PATH).with(AccessTokens.customer(customerId)))
                .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    @DisplayName("지금 사전예약 상품인 주문상품은 빠진다 — 쓰기가 409 로 거절하는 것은 내보내지 않는다")
    void preorderProductsAreLeftOut() throws Exception {
        UUID preorderProduct = fixtures.product("PREORDER", "ACTIVE");
        OrderClient.DeliveredItem kept = item(productId, "2026-10-06T06:00:00Z");
        stubDelivered(item(preorderProduct, "2026-10-07T06:00:00Z"), kept);

        mockMvc.perform(get(PATH).with(AccessTokens.customer(customerId)))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[*].orderItemId", contains(kept.orderItemId().toString())));
    }

    @Test
    @DisplayName("고른 뒤 쪽을 나눈다 — total 은 고른 수, 마지막 쪽 너머는 빈 목록")
    void pagesAfterFiltering() throws Exception {
        List<OrderClient.DeliveredItem> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            items.add(item(productId, "2026-10-0%dT06:00:00Z".formatted(9 - i)));
        }
        stubDelivered(items.toArray(OrderClient.DeliveredItem[]::new));

        mockMvc.perform(get(PATH).param("page", "1").param("size", "2").with(AccessTokens.customer(customerId)))
                .andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.items[*].orderItemId",
                        contains(items.get(2).orderItemId().toString(), items.get(3).orderItemId().toString())));
        mockMvc.perform(get(PATH).param("page", "2").param("size", "2").with(AccessTokens.customer(customerId)))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        mockMvc.perform(get(PATH).param("page", "3").param("size", "2").with(AccessTokens.customer(customerId)))
                .andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    @DisplayName("배송 완료 주문이 없으면 빈 목록 · total 0, 로그인하지 않으면 401 · 관리자는 403, 쪽은 앞 10,000개 안")
    void emptyAndAccess() throws Exception {
        stubDelivered();
        mockMvc.perform(get(PATH).with(AccessTokens.customer(customerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items", hasSize(0)));
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH).with(AccessTokens.admin())).andExpect(status().isForbidden());
        mockMvc.perform(get(PATH).param("size", "100").param("page", "100").with(AccessTokens.customer(customerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.violations[0].field").value("page"));
    }

    @Test
    @DisplayName("order 가 catalog 에 없는 상품을 주면 연동 오류(500)다 — 쓰기와 같다")
    void unknownProductIsAContractViolation() throws Exception {
        stubDelivered(item(UUID.randomUUID(), "2026-10-06T06:00:00Z"));

        mockMvc.perform(get(PATH).with(AccessTokens.customer(customerId)))
                .andExpect(status().isInternalServerError());
    }

    private void stubDelivered(OrderClient.DeliveredItem... items) {
        given(orders.getDeliveredItems()).willReturn(ApiResponse.ok(new OrderClient.DeliveredItems(List.of(items), false)));
    }

    private static OrderClient.DeliveredItem item(UUID product, String orderedAt) {
        return new OrderClient.DeliveredItem(UUID.randomUUID(), UUID.randomUUID(), product, UUID.randomUUID(), "주문 때 이름", "블랙 / 256GB",
                "https://img.test/a.webp", 1, new BigDecimal("1250000"), Instant.parse(orderedAt));
    }
}
