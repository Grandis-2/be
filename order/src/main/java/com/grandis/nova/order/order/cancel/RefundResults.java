package com.grandis.nova.order.order.cancel;

import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.PaymentAttention;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.enums.OrderTrigger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderTransition;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.outbox.PreorderOrderSettled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 환불 결과를 주문에 반영한다. 완료면 취소 중 → 취소, 확정 실패면 취소 중에 둔 채 환불 실패 시각과 이력을 남긴다(U1 — 사용자에겐
 * 실패 사실만, 상세 오류는 payment 의 거래 행 · 관리자). 같은 결과를 다시 받아도 한 번만 반영된다(원장 전제 · 실패 시각). 결과 하나 = 트랜잭션 하나.
 *
 * 사전예약 주문의 환불이 끝나면 같은 트랜잭션에서 preorder 에 취소 결과(PREORDER_ORDER_SETTLED CANCELED)를 알린다 — 실을 순번은
 * 취소 중으로 바꿀 때 주문에 적은 값이고, 알렸다는 표식도 함께 적어 늦춰 다시 받는 예약 취소 요청이 결과를 또 적지 않게 한다.
 * 순번이 없으면(구버전이 취소 중으로 바꾼 주문) 알리지 않고, 그 결과는 다시 받은 요청이 적는다({@link SettlePreorderCancelService}).
 *
 * 늦은 승인의 자동 환불(결과를 모른 채 취소한 장바구니 · 바로 구매 주문, PaymentResults)은 주문이 이미 취소라 상태를 바꾸지 않는다 — 완료면
 * 완료 시각과 이력(REFUND_COMPLETED, 취소 → 취소)을 한 번 남기고, 확정 실패면 환불 실패 시각과 이력을 남기고 ERROR · 지표
 * ({@link PaymentAttention})로 경보한다. 돈이 돌아가지 않은 채 주문은 끝나 있어 사람이 해소한다 — 관리자가 다시 보내거나 직접 환불을 기록하면
 * 그 완료가 여기로 와 실패 표시가 풀린다.
 *
 * 그 밖의 환불은 취소 중일 때만 요청되고 취소 중은 환불 완료로만 끝나므로, 완료가 왔는데 주문이 취소 중도 취소됨도 아니면 버그 · 수동 수정의
 * 신호다 — ERROR 로 경보하고 예외로 올리지 않는다(다시 받아도 같고 DLQ 만 채운다). 돈의 사실은 payment 쪽(payments REFUNDED)에 있다.
 */
@Service
public class RefundResults {

    private static final Logger log = LoggerFactory.getLogger(RefundResults.class);

    static final Set<OrderStatus> CANCELING = EnumSet.of(OrderStatus.CANCELING);
    static final EventCause REFUND_COMPLETED = EventCause.system("REFUND_COMPLETED");

    private final OrderLedger ledger;
    private final OrderReader orderReader;
    private final OutboxWriter outboxWriter;
    private final PaymentAttention attention;
    private final TransactionTemplate writeTransaction;

    public RefundResults(OrderLedger ledger, OrderReader orderReader, OutboxWriter outboxWriter, PaymentAttention attention,
                         PlatformTransactionManager transactionManager) {
        this.ledger = ledger;
        this.orderReader = orderReader;
        this.outboxWriter = outboxWriter;
        this.attention = attention;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    /** @throws IllegalArgumentException 주문이 없다 */
    public void settle(RefundSettlement settlement) {
        Order order = orderReader.findById(settlement.orderId())
                .orElseThrow(() -> new IllegalArgumentException("주문이 없다: " + settlement.orderId()));
        if (order.totalAmount().amount().compareTo(settlement.amount()) != 0) {
            log.error("환불 결과의 금액이 주문 총액과 다르다 orderId={} amount={} total={}",
                    order.id(), settlement.amount(), order.totalAmount().amount());
        }
        switch (settlement.result()) {
            case REFUNDED -> refunded(order);
            case FAILED -> failed(order.id());
        }
    }

    private void refunded(Order order) {
        Refunded refunded = writeTransaction.execute(status -> {
            OrderTransition transition =
                    ledger.fire(order.id(), OrderTrigger.REFUND_COMPLETED, CANCELING, REFUND_COMPLETED);
            if (!transition.applied() && transition.status() == OrderStatus.CANCELED) {
                return new Refunded(transition, false, ledger.noteAutoRefundCompleted(order.id(), REFUND_COMPLETED));
            }
            return new Refunded(transition, transition.applied() && reportCanceled(order), false);
        });
        OrderTransition transition = refunded.transition();
        if (!transition.applied() && transition.status() != OrderStatus.CANCELED) {
            log.error("환불이 끝났는데 주문이 취소 중이 아니다 — 확인 필요 orderId={} status={}", order.id(), transition.status());
            return;
        }
        if (!transition.applied() && autoRefunded(order.id())) {
            log.info("환불 결과 반영 — 늦은 승인의 자동 환불 완료 orderId={} recorded={}", order.id(), refunded.autoRefundCompleted());
            return;
        }
        log.info("환불 결과 반영 orderId={} result=REFUNDED applied={} status={}", order.id(), transition.applied(),
                transition.status());
        if (transition.applied() && order.source() == OrderSource.PREORDER && !refunded.preorderReported()) {
            log.info("취소 시도 순번이 없어 취소 결과를 알리지 않음 — 다시 받는 예약 취소 요청이 적는다 orderId={}", order.id());
        }
    }

    /** 사전예약 주문이면 저장한 순번으로 취소 결과를 적는다. 원장이 방금 이 주문을 취소로 바꾼 트랜잭션 안이다. */
    private boolean reportCanceled(Order order) {
        if (order.source() != OrderSource.PREORDER) {
            return false;
        }
        Optional<Long> cancelSequence = ledger.notePreorderCancelReported(order.id());
        cancelSequence.ifPresent(sequence -> outboxWriter.append(
                PreorderOrderSettled.canceled(order.preorderId(), order.preorderToken(), sequence)));
        return cancelSequence.isPresent();
    }

    private void failed(UUID orderId) {
        if (Boolean.TRUE.equals(writeTransaction.execute(status -> ledger.noteRefundFailed(orderId)))) {
            if (autoRefunded(orderId)) {
                log.error("환불 결과 반영 — 늦은 승인의 자동 환불이 확정 실패, 취소된 주문의 돈이 돌아가지 않았다 — 사람 확인 필요 orderId={}",
                        orderId);
                attention.record(PaymentAttention.Reason.AUTO_REFUND_FAILED);
                return;
            }
            log.warn("환불 결과 반영 — 환불 확정 실패, 주문은 취소 중에 남는다 orderId={}", orderId);
            return;
        }
        // 이미 남겼거나(같은 알림 재수신) 취소 중 · 자동 환불한 취소가 아니다 — 후자는 환불 요청 경로상 갈 수 없다
        log.info("환불 결과 반영 — 환불 실패 이력을 남기지 않음(이미 남겼거나 받을 상태가 아님) orderId={}", orderId);
    }

    /** 늦은 승인의 자동 환불을 요청한 주문인가. 요청은 취소된 주문에만 적는다. */
    private boolean autoRefunded(UUID orderId) {
        return orderReader.findAutoRefundRequestedAt(orderId).isPresent();
    }

    /** 환불 완료 트랜잭션의 결과: 원장의 전이와, 그 안에서 preorder 에 취소 결과를 적었는지 · 자동 환불 완료를 적었는지. */
    private record Refunded(OrderTransition transition, boolean preorderReported, boolean autoRefundCompleted) {
    }
}
