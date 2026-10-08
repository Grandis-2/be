package com.grandis.nova.order.order.cancel;

import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.enums.OrderTrigger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderTransition;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.outbox.OrderRefundRequested;
import com.grandis.nova.order.outbox.PreorderOrderSettled;
import com.grandis.nova.order.outbox.PreorderOrderSettled.RejectReason;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * 예약 취소 요청(PREORDER_CANCEL_REQUESTED)에 따른 주문 정리. 결과(PREORDER_ORDER_SETTLED)는 같은 트랜잭션에서 아웃박스에 적는다.
 *
 * 순서: 예약 내부 id 로 주문 조회 → 없으면 NO_ORDER / 있으면 원장 fire(사유별 전제) → 돌려받은 상태로 결과 판정 → 기록.
 * 미결제면 바로 취소, 결제됨(만료 아님)이면 취소 중으로 바꾸고 같은 트랜잭션에서 환불 요청(ORDER_REFUND_REQUESTED)을 적는다 —
 * 결과는 환불이 끝난 뒤(CANCELED) 다시 받은 메시지가 적는다({@link CancelSettlement.Deferred}).
 *
 * - 주문은 봉투의 aggregateId 로 찾고, payload 의 UUID 는 찾은 주문의 preorder_token 과 대조만 한다. 다르면 어느 예약의 결과인지 알 수
 *   없어 적지 않는다.
 * - 결과는 원장이 행을 잠근 뒤 돌려준 status 로 정한다. 조회한 상태로 고르면 그사이 승인된 결제를 미결제로 취소할 수 있다.
 * - 트랜잭션은 여기서 연다(원장 · OutboxWriter 는 MANDATORY). 바깥 트랜잭션 안에서 부르지 않는다 — 부르는 곳은 큐 소비기뿐이다.
 * - 같은 요청을 다시 받으면 전이 · 환불 요청은 상태 머신이 한 번만 하고, 결과는 다시 적는다(preorder 가 cancelSequence 로 멱등 처리).
 */
@Service
public class SettlePreorderCancelService {

    /** 만료 취소의 전제: 미결제만. 그사이 결제됐으면 환불하지 않고 거절한다(만료 경합). */
    static final Set<OrderStatus> UNPAID = EnumSet.of(OrderStatus.AWAITING_PAYMENT);
    /** 그 밖의 취소의 전제: 미결제 → 취소, 결제됨(출고 전) → 취소 중(환불). */
    static final Set<OrderStatus> BEFORE_SHIPMENT = EnumSet.of(OrderStatus.AWAITING_PAYMENT,
            OrderStatus.AWAITING_CONFIRMATION, OrderStatus.PREPARING_ITEMS, OrderStatus.READY_TO_SHIP);
    static final String CAUSE_PREFIX = "PREORDER_CANCEL:";

    private final OrderReader orderReader;
    private final OrderLedger ledger;
    private final OutboxWriter outboxWriter;

    public SettlePreorderCancelService(OrderReader orderReader, OrderLedger ledger, OutboxWriter outboxWriter) {
        this.orderReader = orderReader;
        this.ledger = ledger;
        this.outboxWriter = outboxWriter;
    }

    /**
     * @return 적은 결과, 또는 아직 정할 수 없음(이번에 취소 중으로 바꿨으면 그 전이 · 환불 요청은 커밋된다)
     * @throws IllegalStateException 예약의 회원 또는 예약 UUID 가 주문과 다르다 — 데이터가 어긋났다.
     *                               다시 받아도 같으므로 소비기가 지우지 않고 DLQ 로 보낸다
     */
    @Transactional
    public CancelSettlement settle(SettlePreorderCancelCommand cancel) {
        // 사유는 원장 호출 전에 만든다 — 원장 안에서 검증에 실패하면 트랜잭션이 rollback-only 가 된다.
        EventCause cause = EventCause.system(CAUSE_PREFIX + cancel.reason());
        Optional<Order> found = orderReader.findByPreorderId(cancel.preorderInternalId());
        CancelSettlement settlement;
        if (found.isEmpty()) {
            settlement = new CancelSettlement.Settled(PreorderOrderSettled.noOrder(
                    cancel.preorderInternalId(), cancel.preorderId(), cancel.cancelSequence()));
        } else {
            Order order = found.get();
            if (!order.customerId().equals(cancel.customerId())) {
                throw new IllegalStateException("예약의 회원과 주문의 회원이 다르다: preorderInternalId=%s, orderId=%s"
                        .formatted(cancel.preorderInternalId(), order.id()));
            }
            if (!order.preorderToken().equals(cancel.preorderId())) {
                throw new IllegalStateException(
                        "봉투의 예약과 payload 의 예약 UUID 가 다르다: preorderInternalId=%s, orderId=%s, payload=%s, order=%s"
                                .formatted(cancel.preorderInternalId(), order.id(), cancel.preorderId(),
                                        order.preorderToken()));
            }
            OrderTransition transition =
                    ledger.fire(order.id(), OrderTrigger.CANCEL_REQUESTED, cancelableFrom(cancel.reason()), cause);
            if (transition.applied() && transition.status() == OrderStatus.CANCELING) {
                outboxWriter.append(new OrderRefundRequested(order.id(), order.totalAmount().amount()));
            }
            settlement = settledFor(transition.status(), cancel);
        }
        if (settlement instanceof CancelSettlement.Settled settled) {
            outboxWriter.append(settled.result());
        }
        return settlement;
    }

    /** 사유별 원장 전제. 만료는 미결제만 — 출고 뒤는 어느 사유든 전제 밖이다. */
    static Set<OrderStatus> cancelableFrom(CancelReason reason) {
        return reason == CancelReason.EXPIRY ? UNPAID : BEFORE_SHIPMENT;
    }

    /**
     * 원장이 돌려준 상태로 정한 결과. 상태를 모두 적는다 — default 로 뭉뚱그리지 않아 상태가 늘면 컴파일러가 알린다.
     *
     * @throws IllegalStateException 미결제 · 결제됨(만료 아님)이 원장 뒤에도 남았다 — 전제에 있으므로 원장이 바꿨어야 한다
     */
    static CancelSettlement settledFor(OrderStatus status, SettlePreorderCancelCommand cancel) {
        return switch (status) {
            // 이번에 취소했든, 환불이 끝나 취소됐든, 이미 취소돼 있었든(재수신) 같다.
            case CANCELED -> new CancelSettlement.Settled(PreorderOrderSettled.canceled(
                    cancel.preorderInternalId(), cancel.preorderId(), cancel.cancelSequence()));
            case SHIPPED, DELIVERED -> rejected(cancel, RejectReason.SHIPPED);
            // 만료 취소는 그사이 결제된 것이라(만료 경합) 환불하지 않고 거절한다. 그 밖의 사유면 원장이 취소 중으로 바꿨어야 한다.
            case AWAITING_CONFIRMATION, PREPARING_ITEMS, READY_TO_SHIP -> {
                if (cancel.reason() == CancelReason.EXPIRY) {
                    yield rejected(cancel, RejectReason.PAID);
                }
                throw new IllegalStateException("결제된 주문이 취소 중으로 가지 않았다: preorderInternalId="
                        + cancel.preorderInternalId() + ", status=" + status);
            }
            // 승인 결과가 나온 뒤 다시 받으면 위 줄 중 하나가 된다.
            case AUTHORIZING -> new CancelSettlement.Deferred(status);
            // 환불이 끝나면(CANCELED) 다시 받아 결과를 적는다. 환불이 확정 실패했어도 같다 — 사람이 해소해 취소되면 그때 적는다(U1).
            case CANCELING -> new CancelSettlement.Deferred(status);
            case AWAITING_PAYMENT -> throw new IllegalStateException(
                    "미결제 주문이 취소되지 않았다: preorderInternalId=" + cancel.preorderInternalId());
        };
    }

    private static CancelSettlement rejected(SettlePreorderCancelCommand cancel, RejectReason reason) {
        return new CancelSettlement.Settled(PreorderOrderSettled.rejected(
                cancel.preorderInternalId(), cancel.preorderId(), reason, cancel.cancelSequence()));
    }
}
