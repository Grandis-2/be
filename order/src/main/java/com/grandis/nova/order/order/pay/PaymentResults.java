package com.grandis.nova.order.order.pay;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.order.MySqlLockFailures;
import com.grandis.nova.order.client.payment.DeclineReason;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.PaymentAttention;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.enums.OrderTrigger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderTransition;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.outbox.NotificationRequested;
import com.grandis.nova.order.outbox.OrderRefundRequested;
import com.grandis.nova.order.outbox.PreorderPaymentConfirmed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * 결제 결과를 주문에 반영한다 — 승인 API 의 동기 응답과 결과 이벤트 소비가 같은 길을 쓴다. 둘 다 오거나 순서가 바뀌어도
 * 원장 전제(승인 중)로 한 번만 반영된다. 거절 · 되돌림은 그 결제창의 승인 중일 때만 반영한다(OrderLedger#settlePayment).
 *
 * 결과 하나 = 트랜잭션 하나. 승인을 반영하면 같은 트랜잭션에서 출처별 뒷일을 한다 — 장바구니 · 바로 구매 주문은 판매 확정(장바구니 주문은
 * 장바구니 차감도, {@link CartOrderFulfillment}), 사전예약 주문은 preorder 에 결제 승인 알림(PREORDER_PAYMENT_CONFIRMED)을 아웃박스에 적는다.
 * 사전예약 주문의 거절을 결과 이벤트로 받아 반영하면 회원에게 결제 실패를 알린다 — 승인 응답으로 바로 받은 거절은 화면이 이미 보였다.
 * 반영이 되돌아가면 뒷일도 없고, 승인은 원장의 승인 중 전제로 한 번만 반영되므로 뒷일도 한 번이다 — 승인 응답과 결과 이벤트가 겹쳐도.
 *
 * 승인이 반영되지 못했는데 주문이 결제되지 않은 상태(결제 대기 · 취소됨)면 돈이 나간 채 주문이 받지 못한 것이다. 셋으로 가른다.
 * <ul>
 *   <li>결과를 끝내 몰라 취소한 장바구니 · 바로 구매 주문(UnconfirmedCartOrderExpiry)에 그 결제창의 승인이 늦게 왔다 — 같은 트랜잭션 ·
 *       같은 잠금 안에서 자동 환불을 요청한다(한 번만, 금액이 주문 총액과 같을 때만). D18("자동 환불하지 않는다")의 예외다 — 그 취소는
 *       결과를 모른 채 한 것이라 늦은 승인이 예상된 경우다. 사전예약 주문은 결과를 끝까지 기다리므로 여기에 오지 않는다.</li>
 *   <li>환불로 끝난 취소(이력에 취소 중 → 취소) · 이미 자동 환불을 요청한 취소 — 늦게 온 승인은 중복이다(D17).</li>
 *   <li>그 밖 — 상태 머신으로는 갈 수 없는 경우(되돌림은 그 결제창의 거절 · "앞으로도 시작될 수 없다" 확언으로만 일어난다)거나 결제창 ·
 *       금액이 맞지 않는다. 버그 · 수동 수정의 신호라 ERROR 와 지표({@link PaymentAttention} — 금액이 다르면 AMOUNT_MISMATCH, 그 밖은
 *       UNMATCHED_APPROVAL)로 경보하고 예외로 올리지 않는다(다시 받아도
 *       같고 DLQ 만 채운다). 영속 기록은 payment 쪽에 있다: 그 주문의 payments 와 payment_outbox_events(지우지 않는다).</li>
 * </ul>
 */
@Service
public class PaymentResults {

    private static final Logger log = LoggerFactory.getLogger(PaymentResults.class);

    /** 승인을 이미 반영한 뒤의 상태들. 여기서 승인이 또 오면 중복이다. */
    private static final Set<OrderStatus> PAID = EnumSet.of(OrderStatus.AWAITING_CONFIRMATION,
            OrderStatus.PREPARING_ITEMS, OrderStatus.READY_TO_SHIP, OrderStatus.SHIPPED, OrderStatus.DELIVERED,
            OrderStatus.CANCELING);

    static final int MAX_ATTEMPTS = 3;
    static final EventCause LATE_REFUND = EventCause.system("PAYMENT_LATE_REFUND");

    private final OrderLedger ledger;
    private final OrderReader orderReader;
    private final CartOrderFulfillment fulfillment;
    private final OutboxWriter outboxWriter;
    private final PaymentAttention attention;
    private final TransactionTemplate writeTransaction;

    public PaymentResults(OrderLedger ledger, OrderReader orderReader, CartOrderFulfillment fulfillment,
                          OutboxWriter outboxWriter, PaymentAttention attention, PlatformTransactionManager transactionManager) {
        this.ledger = ledger;
        this.orderReader = orderReader;
        this.fulfillment = fulfillment;
        this.outboxWriter = outboxWriter;
        this.attention = attention;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    /** 결과 이벤트. 금액이 주문 총액과 다르면 ERROR 로 남기고 반영은 한다 — 다만 늦은 승인을 자동 환불하지는 않는다. */
    public OrderTransition settle(PaymentSettlement settlement) {
        Order order = orderReader.findById(settlement.orderId())
                .orElseThrow(() -> new IllegalArgumentException("주문이 없다: " + settlement.orderId()));
        boolean amountMatches = order.totalAmount().amount().compareTo(settlement.amount()) == 0;
        if (!amountMatches) {
            log.error("결제 결과의 금액이 주문 총액과 다르다 orderId={} providerOrderId={} amount={} total={}",
                    order.id(), settlement.providerOrderId(), settlement.amount(), order.totalAmount().amount());
        }
        return switch (settlement.result()) {
            case APPROVED -> approved(order.id(), settlement.providerOrderId(), settlement.approvedAt(), amountMatches);
            case DECLINED -> apply(order.id(), OrderTrigger.PAYMENT_DECLINED, settlement.providerOrderId(), null,
                    declineCause(settlement.declineReason()), false, true).transition();
        };
    }

    /**
     * 승인 API 응답 — 승인 시각이 없어 반영한 시각이 결제 시각이 된다. 금액은 승인을 요청하기 전에 주문 총액과 대조했다
     * (ConfirmPaymentService) — 그래서 늦은 승인이면 자동 환불한다.
     */
    OrderTransition approved(UUID orderId, String providerOrderId) {
        return approved(orderId, providerOrderId, null, true);
    }

    /**
     * @param approvedAt    결제사 승인 시각(결과 이벤트). null 이면 반영한 시각
     * @param amountMatches 승인 금액이 주문 총액과 같은가. 다르면 늦은 승인을 자동 환불하지 않는다
     */
    private OrderTransition approved(UUID orderId, String providerOrderId, Instant approvedAt, boolean amountMatches) {
        Applied applied = apply(orderId, OrderTrigger.PAYMENT_APPROVED, providerOrderId, approvedAt,
                EventCause.system("PAYMENT_APPROVED"), amountMatches, false);
        OrderTransition transition = applied.transition();
        if (transition.applied() || PAID.contains(transition.status())) {
            return transition;
        }
        if (transition.status() == OrderStatus.CANCELED && refunded(orderId)) {
            log.info("환불로 끝난 주문에 늦게 온 승인 — 중복으로 무시 orderId={} providerOrderId={}", orderId, providerOrderId);
            return transition;
        }
        switch (applied.lateRefund()) {
            case REQUESTED -> log.warn("결제 결과를 모른 채 취소한 주문에 늦게 온 승인 — 자동 환불 요청 orderId={} providerOrderId={}",
                    orderId, providerOrderId);
            case ALREADY_REQUESTED -> log.info("자동 환불을 요청한 주문에 늦게 온 승인 — 중복으로 무시 orderId={} providerOrderId={}",
                    orderId, providerOrderId);
            case NOT_ELIGIBLE -> {
                log.error("결제가 승인됐는데 주문이 받지 못했다 — 환불 확인 필요 orderId={} providerOrderId={} status={}",
                        orderId, providerOrderId, transition.status());
                attention.record(PaymentAttention.Reason.UNMATCHED_APPROVAL);
            }
            case AMOUNT_MISMATCH -> {
                log.error("결제 결과를 모른 채 취소한 주문에 늦게 온 승인의 금액이 주문 총액과 다르다 — 자동 환불하지 않음, 환불 확인 필요"
                        + " orderId={} providerOrderId={}", orderId, providerOrderId);
                attention.record(PaymentAttention.Reason.AMOUNT_MISMATCH);
            }
        }
        return transition;
    }

    /** 승인 API 응답의 거절 — 회원은 화면에서 이미 봤으므로 알리지 않는다. */
    OrderTransition declined(UUID orderId, String providerOrderId, DeclineReason reason) {
        return apply(orderId, OrderTrigger.PAYMENT_DECLINED, providerOrderId, null, declineCause(reason), false, false)
                .transition();
    }

    private static EventCause declineCause(DeclineReason reason) {
        return EventCause.system("PAYMENT_DECLINED:" + reason);
    }

    /**
     * 승인을 이번에 반영했을 때만, 그 트랜잭션에서. 재고 장부 어긋남은 반영을 막지 않고 ERROR 로 알린다(돈을 따른다). 장바구니 차감의
     * 잠근 줄 갱신이 1행이 아니면(잠금 규칙이 깨짐) · 알림을 적지 못하면 승인 반영도 되돌아가 다시 받는다.
     * 원장이 바꾼 주문 엔티티를 떼어 냈으므로 여기서 읽는 주문은 결제 시각까지 반영된 DB 값이다.
     */
    private void onApproved(UUID orderId, String providerOrderId) {
        Order paid = orderReader.findById(orderId).orElseThrow(() -> new IllegalStateException("주문이 사라졌다: " + orderId));
        if (paid.source() == OrderSource.PREORDER) {
            outboxWriter.append(new PreorderPaymentConfirmed(paid.preorderId(), paid.preorderToken(), paid.id(),
                    paid.paidAt()));
            return;
        }
        fulfillment.onApproved(paid, providerOrderId);
    }

    /**
     * 사전예약 주문의 거절을 이번에 반영했을 때만, 그 트랜잭션에서 회원에게 결제 실패를 알린다. 장바구니 · 바로 구매 주문은 알리지 않는다 —
     * 결제 대기로 돌아가 기한이 지나면 만료로 취소된다.
     */
    private void onDeclined(UUID orderId) {
        Order declined = orderReader.findById(orderId).orElseThrow(() -> new IllegalStateException("주문이 사라졌다: " + orderId));
        if (declined.source() == OrderSource.PREORDER) {
            outboxWriter.append(NotificationRequested.preorderPaymentFailed(declined.id(), declined.customerId(),
                    declined.preorderToken()));
        }
    }

    /**
     * 결과를 모른 채 취소한 주문에 늦게 온 승인 — 원장이 받으면(결제창이 같고 처음) 같은 트랜잭션에서 주문 총액 전액의 환불을 요청한다.
     * payment 는 그 주문의 하나뿐인 성공 결제를 환불한다(대상당 성공 결제 하나). 결과는 환불 결과 반영이 받는다(RefundResults).
     */
    private OrderLedger.LateRefund requestLateRefund(UUID orderId, String providerOrderId, boolean amountMatches) {
        OrderLedger.LateRefund late = ledger.requestLateRefund(orderId, providerOrderId, amountMatches, LATE_REFUND);
        if (late == OrderLedger.LateRefund.REQUESTED) {
            Order canceled = orderReader.findById(orderId).orElseThrow(() -> new IllegalStateException("주문이 사라졌다: " + orderId));
            outboxWriter.append(new OrderRefundRequested(orderId, canceled.totalAmount().amount()));
        }
        return late;
    }

    /** 환불을 거쳐 취소됐는가(이력에 취소 중 → 취소). 취소 중은 결제된 주문만 들어간다. */
    private boolean refunded(UUID orderId) {
        return orderReader.findEvents(orderId, Long.MAX_VALUE).stream().anyMatch(event ->
                event.fromStatus() == OrderStatus.CANCELING && event.toStatus() == OrderStatus.CANCELED);
    }

    /** payment 가 이 결제창은 앞으로도 시작될 수 없다고 확언했다 — 결제 대기로 되돌린다. 이력에서 거절과 가른다. 알리지 않는다. */
    OrderTransition notStarted(UUID orderId, String providerOrderId) {
        return apply(orderId, OrderTrigger.PAYMENT_DECLINED, providerOrderId, null, EventCause.system("PAYMENT_NOT_STARTED"),
                false, false).transition();
    }

    /**
     * 교착(1213)이면 새 트랜잭션에서 다시 한다(최대 {@link #MAX_ATTEMPTS}번). 장바구니 주문의 승인은 주문 행 → 장바구니 줄 → 재고 행 순서로
     * 잠그는데, 같은 회원의 장바구니 주문 생성은 장바구니 줄 → 앞 주문 행 순서라 결제 반영 순간 다시 주문하면 서로 기다릴 수 있다. 다시 하기는
     * 원장의 승인 중 전제로 한 번만 반영된다. 바깥 트랜잭션 안에서 불리면 새 트랜잭션을 열 수 없으므로 다시 하지 않는다.
     *
     * 늦은 승인의 자동 환불 판정도 이 트랜잭션 안에서 한다 — 원장이 잡은 주문 행 잠금 안이라, 그사이 다른 반영이 끼어들지 않는다.
     *
     * @param refundIfLate   승인이 반영되지 못해 취소된 주문이면 자동 환불을 요청해도 되는가(승인 금액이 주문 총액과 같을 때만 true —
     *                       false 면 요청하지 않고 금액 불일치로 가른다)
     * @param notifyDeclined 거절을 반영하면 사전예약 회원에게 알릴지(결과 이벤트의 거절만 true)
     */
    private Applied apply(UUID orderId, OrderTrigger result, String providerOrderId, Instant approvedAt, EventCause cause,
                          boolean refundIfLate, boolean notifyDeclined) {
        int attempts = TransactionSynchronizationManager.isActualTransactionActive() ? 1 : MAX_ATTEMPTS;
        Applied applied;
        for (int attempt = 1; ; attempt++) {
            try {
                applied = writeTransaction.execute(status -> {
                    OrderTransition settled = ledger.settlePayment(orderId, result, providerOrderId, approvedAt, cause);
                    OrderLedger.LateRefund late = OrderLedger.LateRefund.NOT_ELIGIBLE;
                    if (result == OrderTrigger.PAYMENT_APPROVED) {
                        if (settled.applied()) {
                            onApproved(orderId, providerOrderId);
                        } else if (settled.status() == OrderStatus.CANCELED) {
                            late = requestLateRefund(orderId, providerOrderId, refundIfLate);
                        }
                    } else if (settled.applied() && notifyDeclined) {
                        onDeclined(orderId);
                    }
                    return new Applied(settled, late);
                });
                break;
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= attempts || !MySqlLockFailures.isDeadlock(e)) {
                    // 잠금 대기 초과 · 다시 해도 교착 — 일시 장애다(503). 승인 응답 경로는 다시 부르고, 결과 이벤트는 다시 받는다
                    log.warn("결제 결과 반영 잠금 실패, 포기 orderId={} attempt={}", orderId, attempt, e);
                    throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
                }
                log.info("결제 결과 반영 교착, 다시 시도 {}/{} orderId={}", attempt, attempts, orderId);
            }
        }
        OrderTransition transition = applied.transition();
        log.info("결제 결과 반영 orderId={} providerOrderId={} result={} applied={} status={}",
                orderId, providerOrderId, result, transition.applied(), transition.status());
        return applied;
    }

    /** 결과 반영 트랜잭션의 결과: 원장의 전이와, 늦은 승인이면 자동 환불 판정. */
    private record Applied(OrderTransition transition, OrderLedger.LateRefund lateRefund) {
    }
}
