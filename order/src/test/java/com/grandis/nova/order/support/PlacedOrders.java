package com.grandis.nova.order.support;

import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.enums.OrderTrigger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.vo.EventCause;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.EnumSet;

/**
 * 조회 테스트용 주문. 원장으로 만들고 바꾼다 — 조회가 보는 행 · 이력이 운영과 같은 길로 생긴다.
 * 원장은 호출자의 트랜잭션을 요구하므로(MANDATORY) 호출마다 트랜잭션을 연다.
 */
public class PlacedOrders {

    private final OrderLedger ledger;
    private final TransactionTemplate transactionTemplate;
    private final OrderFixtures fixtures;

    public PlacedOrders(OrderLedger ledger, TransactionTemplate transactionTemplate, OrderFixtures fixtures) {
        this.ledger = ledger;
        this.transactionTemplate = transactionTemplate;
        this.fixtures = fixtures;
    }

    /** 그 회원의 새 예약으로 주문 하나. 회원은 상품마다 진행 중인 예약이 하나라(uq_preorder_active) 상품도 새로 만든다. */
    public Order place(Long customerId) {
        OrderFixtures.PreorderProduct product = fixtures.preorderProduct();
        Long preorderId = fixtures.payablePreorder(customerId, product, 1);
        return transactionTemplate.execute(status -> ledger.place(
                OrderFixtures.preorderCommand(customerId, preorderId, product).toDraft(), EventCause.user()));
    }

    /** 예약 취소 수신과 같은 전이(미결제 → 취소). */
    public void cancel(Long orderId, String reason) {
        transactionTemplate.executeWithoutResult(status -> ledger.fire(orderId, OrderTrigger.CANCEL_REQUESTED,
                EnumSet.of(OrderStatus.AWAITING_PAYMENT), EventCause.system(reason)));
    }
}
