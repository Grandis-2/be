package com.grandis.nova.order.order.api;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.client.member.MemberClient;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.PlacedOrders;
import com.grandis.nova.order.support.TestAuth;
import com.grandis.nova.order.web.PageSizes;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 주문 목록 · 상세의 회원 이름(customerName). member 내부 API 자리만 바꾸고 그 앞(캐시 · 회로)은 실제 빈이다.
 * 회원 id 는 시험마다 새로 만든다 — 이름 캐시가 컨텍스트에 남아 다른 시험의 회원과 섞이지 않게.
 */
@OrderIntegrationTest
@AutoConfigureMockMvc
@DisplayName("관리자 주문 조회의 회원 이름 — member 에게 한 화면에 한 번, 받지 못하면 이름만 null")
class AdminOrderCustomerNameApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired OrderLedger ledger;
    @Autowired TransactionTemplate transactionTemplate;

    @MockitoBean MemberClient memberClient;

    OrderFixtures fixtures;
    PlacedOrders orders;

    @BeforeEach
    void setUp() {
        fixtures = new OrderFixtures(jdbcTemplate);
        orders = new PlacedOrders(ledger, transactionTemplate, fixtures);
    }

    @Test
    @DisplayName("목록은 그 쪽의 회원 id 를 한 번에 묻는다 — 같은 회원은 한 번, 이름은 줄마다 붙는다")
    void listAsksOncePerPage() throws Exception {
        UUID kim = fixtures.customer();
        UUID lee = fixtures.customer();
        Order kimFirst = orders.place(kim);
        Order leeOrder = orders.place(lee);
        Order kimSecond = orders.place(kim);
        when(memberClient.getNames(any())).thenAnswer(invocation -> names(invocation.getArgument(0), kim, "김**", lee, "이**"));

        String body = mockMvc.perform(get("/api/v1/admin/orders").param("size", "" + PageSizes.MAX).with(TestAuth.admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(nameOf(body, kimFirst)).containsExactly("김**");
        assertThat(nameOf(body, kimSecond)).containsExactly("김**");
        assertThat(nameOf(body, leeOrder)).containsExactly("이**");
        verify(memberClient, times(1)).getNames(argThat(ids -> ids.size() == ids.stream().distinct().count()
                && ids.containsAll(List.of(kim, lee))));
    }

    @Test
    @DisplayName("상세도 이름을 싣는다. 캐시에 있는 회원이면 member 를 다시 부르지 않는다")
    void detailCarriesNameAndUsesCache() throws Exception {
        UUID park = fixtures.customer();
        Order order = orders.place(park);
        when(memberClient.getNames(any())).thenAnswer(invocation -> names(invocation.getArgument(0), park, "박**"));

        mockMvc.perform(get("/api/v1/admin/orders/" + order.id()).with(TestAuth.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value(park.toString()))
                .andExpect(jsonPath("$.data.customerName").value("박**"));
        clearInvocations(memberClient);
        mockMvc.perform(get("/api/v1/admin/orders/" + order.id()).with(TestAuth.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerName").value("박**"));
        verify(memberClient, times(0)).getNames(any());
    }

    @Test
    @DisplayName("member 가 답하지 않으면 목록 · 상세는 그대로 200 이고 이름만 null 이다")
    void memberFailureLeavesNameNull() throws Exception {
        UUID choi = fixtures.customer();
        Order order = orders.place(choi);
        when(memberClient.getNames(any())).thenThrow(new ResourceAccessException("member 연결 실패"));

        String body = mockMvc.perform(get("/api/v1/admin/orders").param("customerId", choi.toString()).with(TestAuth.admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(body, "$.data.items[?(@.orderId == '%s')].customerName".formatted(order.id())))
                .containsExactly((Object) null);
        mockMvc.perform(get("/api/v1/admin/orders/" + order.id()).with(TestAuth.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value(choi.toString()))
                .andExpect(jsonPath("$.data.customerName").doesNotExist());
    }

    private static List<String> nameOf(String body, Order order) {
        return JsonPath.read(body, "$.data.items[?(@.orderId == '%s')].customerName".formatted(order.id()));
    }

    /** 물은 id 중 아는 회원만 담은 member 응답. */
    private static ApiResponse<MemberClient.Names> names(Collection<UUID> asked, Object... idAndNames) {
        List<MemberClient.Name> items = new java.util.ArrayList<>();
        for (int i = 0; i < idAndNames.length; i += 2) {
            UUID id = (UUID) idAndNames[i];
            if (asked.contains(id)) {
                items.add(new MemberClient.Name(id, (String) idAndNames[i + 1]));
            }
        }
        return ApiResponse.ok(new MemberClient.Names(items));
    }
}
