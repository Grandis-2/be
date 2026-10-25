package com.grandis.nova.order.order.cancel;

import com.grandis.nova.order.client.payment.PaymentRefundAdmin;
import com.grandis.nova.order.client.payment.PaymentRefundIssue;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.enums.OrderTrigger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.support.OrderFixtures;
import com.grandis.nova.order.support.OrderIntegrationTest;
import com.grandis.nova.order.support.PlacedOrders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * 멈춘 환불 목록의 상한 · 나눠 묻기. 두 쪽 후보를 합쳐 오래 멈춘 것부터 자르고, 주문 쪽 후보는 payment 에 나눠 묻는다. 상한 · 나눠 묻는 수는
 * 시험에서 낮춘다(package 범위 오버로드) — 상한까지 주문을 만들면 공유 DB 의 다른 시험(실행 계획)에 끼어든다.
 */
@OrderIntegrationTest
class RefundIssuesLimitTest {

    @Autowired RefundIssues refundIssues;
    @Autowired RefundResults refundResults;
    @Autowired OrderLedger ledger;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean PaymentRefundAdmin payments;

    @Test
    void 주문_쪽_후보는_payment_에_나눠_묻는다() {
        OrderFixtures fixtures = new OrderFixtures(jdbcTemplate);
        PlacedOrders orders = new PlacedOrders(ledger, transactionTemplate, fixtures);
        UUID customerId = fixtures.customer();
        List<UUID> stuck = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            stuck.add(stuckOrder(orders, customerId).id());
        }
        given(payments.issues(eq(List.of()), anyInt(), any())).willReturn(List.of());
        List<Integer> asked = new java.util.ArrayList<>();
        given(payments.issues(argThat(ids -> !ids.isEmpty()), anyInt(), any())).willAnswer(invocation -> {
            List<UUID> ids = invocation.getArgument(0);
            asked.add(ids.size());
            return ids.stream().map(id -> new PaymentRefundIssue(id, "REFUND_REJECTED", "SUCCEEDED", null, List.of())).toList();
        });

        List<RefundIssues.RefundIssue> issues = refundIssues.list(1, null, RefundIssues.MAX, 2);

        assertThat(issues.stream().map(RefundIssues.RefundIssue::orderId)).containsAll(stuck);
        assertThat(asked).allMatch(size -> size <= 2).hasSizeGreaterThanOrEqualTo(3);
    }

    private Order stuckOrder(PlacedOrders orders, UUID customerId) {
        Order order = orders.place(customerId);
        orders.pay(order.id());
        transactionTemplate.executeWithoutResult(status -> ledger.fire(order.id(), OrderTrigger.CANCEL_REQUESTED,
                EnumSet.of(OrderStatus.AWAITING_CONFIRMATION), EventCause.system("PREORDER_CANCEL:USER")));
        refundResults.settle(new RefundSettlement(order.id(), RefundSettlement.Result.FAILED, order.totalAmount().amount()));
        return order;
    }

    @Test
    void 주문_쪽_후보가_상한을_채워도_더_오래_멈춘_결제_쪽_후보가_보인다() {
        OrderFixtures fixtures = new OrderFixtures(jdbcTemplate);
        PlacedOrders orders = new PlacedOrders(ledger, transactionTemplate, fixtures);
        UUID customerId = fixtures.customer();
        for (int i = 0; i < 3; i++) {
            Order order = orders.place(customerId);
            orders.pay(order.id());
            transactionTemplate.executeWithoutResult(status -> ledger.fire(order.id(), OrderTrigger.CANCEL_REQUESTED,
                    EnumSet.of(OrderStatus.AWAITING_CONFIRMATION), EventCause.system("PREORDER_CANCEL:USER")));
            refundResults.settle(new RefundSettlement(order.id(), RefundSettlement.Result.FAILED, order.totalAmount().amount()));
        }
        UUID paymentOnly = UUID.randomUUID();
        Instant longAgo = Instant.parse("2020-01-01T00:00:00Z");
        given(payments.issues(eq(List.of()), anyInt(), any())).willReturn(List.of(new PaymentRefundIssue(paymentOnly,
                "ESCALATED_TIMED_OUT", "SUCCEEDED", new PaymentRefundIssue.Refund(UUID.randomUUID(), "PROCESSING", 3, longAgo,
                null, longAgo, "RECOVERY_UNRESOLVED", null, longAgo), List.of("RETRY", "MANUAL_COMPLETE"))));
        given(payments.issues(argThat(ids -> !ids.isEmpty()), anyInt(), any())).willReturn(List.of());

        List<RefundIssues.RefundIssue> issues = refundIssues.list(1, null, 3, RefundIssues.LOOKUP_CHUNK);

        assertThat(issues).hasSize(3);
        assertThat(issues.getFirst().orderId()).as("가장 오래 멈춘 결제 쪽 후보가 맨 앞").isEqualTo(paymentOnly);
    }
}
