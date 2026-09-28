package com.grandis.nova.order.order.api;

import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.PlacedOrders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@OrderIntegrationTest
@AutoConfigureMockMvc
class OrderCancelabilityApiTest {

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

    /* 결제 전 예약은 주문이 없다. 정리할 주문이 없으니 취소해도 된다. */
    @Test
    void noOrderIsCancelable() throws Exception {
        Long preorderId = fixtures.payablePreorder(customerId, fixtures.preorderProduct(), 1);

        mockMvc.perform(cancelability(preorderId).with(me()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderStatus").isEmpty())
                .andExpect(jsonPath("$.data.cancelable").value(true))
                .andExpect(jsonPath("$.data.reason").isEmpty());
    }

    @Test
    void awaitingPaymentIsCancelable() throws Exception {
        Order order = orders.place(customerId);

        mockMvc.perform(cancelability(order.preorderId()).with(me()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderStatus").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.data.cancelable").value(true))
                .andExpect(jsonPath("$.data.reason").isEmpty());
    }

    /* 이미 취소됐어도 가능이다 — 다시 취소해도 결과가 같다. */
    @Test
    void canceledIsCancelable() throws Exception {
        Order order = orders.place(customerId);
        orders.cancel(order.id(), "USER_REQUEST");

        mockMvc.perform(cancelability(order.preorderId()).with(me()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderStatus").value("CANCELED"))
                .andExpect(jsonPath("$.data.cancelable").value(true))
                .andExpect(jsonPath("$.data.reason").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHIPPED", "DELIVERED"})
    void afterShipmentIsNotCancelable(String shipped) throws Exception {
        Order order = orders.place(customerId);
        fixtures.forceStatus(order.id(), shipped);

        mockMvc.perform(cancelability(order.preorderId()).with(me()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderStatus").value(shipped))
                .andExpect(jsonPath("$.data.cancelable").value(false))
                .andExpect(jsonPath("$.data.reason").value("SHIPPED"));
    }

    /* order 는 예약 공개 UUID 를 모른다. 호출자는 cancelable · orderStatus 만 쓴다. */
    @Test
    void responseCarriesNoPreorderIdentifier() throws Exception {
        Order order = orders.place(customerId);

        mockMvc.perform(cancelability(order.preorderId()).with(me()))
                .andExpect(jsonPath("$.data.preorderId").doesNotExist())
                .andExpect(jsonPath("$.data.preorderInternalId").doesNotExist());
    }

    /* preorder 가 403 을 사용자에게 404 로 바꿔 존재를 숨긴다. 판정 값은 내보내지 않는다. */
    @Test
    void someoneElsesOrderIsForbidden() throws Exception {
        Order others = orders.place(fixtures.customer());

        mockMvc.perform(cancelability(others.preorderId()).with(me()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void adminSeesAnyOrder() throws Exception {
        Order others = orders.place(fixtures.customer());
        fixtures.forceStatus(others.id(), "SHIPPED");

        mockMvc.perform(cancelability(others.preorderId()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderStatus").value("SHIPPED"))
                .andExpect(jsonPath("$.data.cancelable").value(false));
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        Order order = orders.place(customerId);

        mockMvc.perform(cancelability(order.preorderId())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/orders/by-preorder/abc/cancelability")).andExpect(status().isUnauthorized());
    }

    /*
     * /internal/** 규칙이 없으면 anyRequest().permitAll() 로 떨어져 인증 없이 열린다. 위 401 은 @CurrentViewer 리졸버도
     * 내므로 규칙을 증명하지 못한다 — 리졸버가 없는 자리(매핑 없는 경로)에서 보안 설정이 먼저 막는지 본다(규칙이 없으면 404).
     * 보안 설정의 401 은 본문이 없다(리졸버의 401 은 봉투가 있다).
     */
    @Test
    void securityRuleGuardsWholeInternalPath() throws Exception {
        mockMvc.perform(get("/internal/orders/nope")).andExpect(status().isUnauthorized());
        mockMvc.perform(cancelability(1L))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    void malformedPreorderIdIsBadRequest() throws Exception {
        mockMvc.perform(get("/internal/orders/by-preorder/9f1c2d3e-uuid/cancelability").with(me()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value("preorderInternalId"));
    }

    /* 새 규칙이 기존 경로 권한을 바꾸지 않는다. */
    @Test
    void existingPathRulesAreUnchanged() throws Exception {
        mockMvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/orders").with(me())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/orders").with(me())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/orders").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder cancelability(Long preorderId) {
        return get("/internal/orders/by-preorder/{preorderInternalId}/cancelability", preorderId);
    }

    /*
     * 임시 인증 방식 — 임시 리졸버(order.web)는 SecurityContext 의 이름을 회원 id 로 읽는다.
     * common:security 도입 시: user(...) 를 authentication(new NovaAuthentication(...)) 로 바꾼다(OrderQueryApiTest 참고).
     */
    private RequestPostProcessor me() {
        return user(customerId.toString()).roles("USER");
    }
}
