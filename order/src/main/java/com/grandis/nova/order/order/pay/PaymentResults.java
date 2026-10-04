package com.grandis.nova.order.order.pay;

import com.grandis.nova.order.client.payment.DeclineReason;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.enums.OrderTrigger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderTransition;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.vo.EventCause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.EnumSet;
import java.util.Set;

/**
 * 결제 결과를 주문에 반영한다 — 승인 API 의 동기 응답과 결과 이벤트 소비가 같은 길을 쓴다. 둘 다 오거나 순서가 바뀌어도
 * 원장 전제(승인 중)로 한 번만 반영된다. 거절 · 되돌림은 그 결제창의 승인 중일 때만 반영한다(OrderLedger#settlePayment).
 *
 * 결과 하나 = 트랜잭션 하나.
 *
 * 승인이 반영되지 못했는데 주문이 결제되지 않은 상태(결제 대기 · 취소됨)면 돈이 나간 채 주문이 받지 못한 것이다. 상태 머신으로는
 * 갈 수 없는 경우다 — 승인 중에는 취소를 받지 않고, 되돌림은 그 결제창의 거절 · "앞으로도 시작될 수 없다" 확언으로만 일어난다.
 * 버그 · 수동 수정의 신호라 ERROR 로 경보하고 예외로 올리지 않는다(다시 받아도 같고 DLQ 만 채운다). 예외는 환불로 끝난 취소다 —
 * 이력에 취소 중 → 취소가 있으면 결제 · 환불을 이미 거친 주문이라 늦게 온 승인은 중복이다(D17).
 * 영속 기록은 payment 쪽에 있다: 그 주문의 payments 와 payment_outbox_events(지우지 않는다). 운영 대조는 "SUCCEEDED 결제가 있는데
 * 결제되지 않은 주문" 을 거기서 찾는다(D18 — 자동 환불하지 않는다).
 */
@Service
public class PaymentResults {

    private static final Logger log = LoggerFactory.getLogger(PaymentResults.class);

    /** 승인을 이미 반영한 뒤의 상태들. 여기서 승인이 또 오면 중복이다. */
    private static final Set<OrderStatus> PAID = EnumSet.of(OrderStatus.AWAITING_CONFIRMATION,
            OrderStatus.PREPARING_ITEMS, OrderStatus.READY_TO_SHIP, OrderStatus.SHIPPED, OrderStatus.DELIVERED,
            OrderStatus.CANCELING);

    private final OrderLedger ledger;
    private final OrderReader orderReader;
    private final TransactionTemplate writeTransaction;

    public PaymentResults(OrderLedger ledger, OrderReader orderReader, PlatformTransactionManager transactionManager) {
        this.ledger = ledger;
        this.orderReader = orderReader;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    /** 결과 이벤트. 금액이 주문 총액과 다르면 ERROR 로 남기고 반영은 한다. */
    public OrderTransition settle(PaymentSettlement settlement) {
        Order order = orderReader.findById(settlement.orderId())
                .orElseThrow(() -> new IllegalArgumentException("주문이 없다: " + settlement.orderId()));
        if (order.totalAmount().amount().compareTo(settlement.amount()) != 0) {
            log.error("결제 결과의 금액이 주문 총액과 다르다 orderId={} providerOrderId={} amount={} total={}",
                    order.id(), settlement.providerOrderId(), settlement.amount(), order.totalAmount().amount());
        }
        return switch (settlement.result()) {
            case APPROVED -> approved(order.id(), settlement.providerOrderId());
            case DECLINED -> declined(order.id(), settlement.providerOrderId(), settlement.declineReason());
        };
    }

    OrderTransition approved(Long orderId, String providerOrderId) {
        OrderTransition transition = apply(orderId, OrderTrigger.PAYMENT_APPROVED, providerOrderId,
                EventCause.system("PAYMENT_APPROVED"));
        if (!transition.applied() && !PAID.contains(transition.status())) {
            if (transition.status() == OrderStatus.CANCELED && refunded(orderId)) {
                log.info("환불로 끝난 주문에 늦게 온 승인 — 중복으로 무시 orderId={} providerOrderId={}", orderId, providerOrderId);
                return transition;
            }
            log.error("결제가 승인됐는데 주문이 받지 못했다 — 환불 확인 필요 orderId={} providerOrderId={} status={}",
                    orderId, providerOrderId, transition.status());
        }
        return transition;
    }

    OrderTransition declined(Long orderId, String providerOrderId, DeclineReason reason) {
        return apply(orderId, OrderTrigger.PAYMENT_DECLINED, providerOrderId,
                EventCause.system("PAYMENT_DECLINED:" + reason));
    }

    /** 환불을 거쳐 취소됐는가(이력에 취소 중 → 취소). 취소 중은 결제된 주문만 들어간다. */
    private boolean refunded(Long orderId) {
        return orderReader.findEvents(orderId, Long.MAX_VALUE).stream().anyMatch(event ->
                event.fromStatus() == OrderStatus.CANCELING && event.toStatus() == OrderStatus.CANCELED);
    }

    /** payment 가 이 결제창은 앞으로도 시작될 수 없다고 확언했다 — 결제 대기로 되돌린다. 이력에서 거절과 가른다. */
    OrderTransition notStarted(Long orderId, String providerOrderId) {
        return apply(orderId, OrderTrigger.PAYMENT_DECLINED, providerOrderId, EventCause.system("PAYMENT_NOT_STARTED"));
    }

    private OrderTransition apply(Long orderId, OrderTrigger result, String providerOrderId, EventCause cause) {
        OrderTransition transition = writeTransaction.execute(status ->
                ledger.settlePayment(orderId, result, providerOrderId, cause));
        log.info("결제 결과 반영 orderId={} providerOrderId={} result={} applied={} status={}",
                orderId, providerOrderId, result, transition.applied(), transition.status());
        return transition;
    }
}
