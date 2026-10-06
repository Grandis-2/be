package com.grandis.nova.order.order.cancel;

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
 * 환불 결과를 주문에 반영한다. 완료면 취소 중 → 취소, 확정 실패면 취소 중에 둔 채 환불 실패를 이력에 남긴다(U1 — 사용자에겐 실패 사실만,
 * 상세 오류는 payment 의 거래 행 · 관리자). 같은 결과를 다시 받아도 원장 전제(취소 중)로 한 번만 반영된다. 결과 하나 = 트랜잭션 하나.
 *
 * 환불은 취소 중일 때만 요청되고 취소 중은 환불 완료로만 끝나므로, 완료가 왔는데 주문이 취소 중도 취소됨도 아니면 버그 · 수동 수정의
 * 신호다 — ERROR 로 경보하고 예외로 올리지 않는다(다시 받아도 같고 DLQ 만 채운다). 돈의 사실은 payment 쪽(payments REFUNDED)에 있다.
 */
@Service
public class RefundResults {

    private static final Logger log = LoggerFactory.getLogger(RefundResults.class);

    static final Set<OrderStatus> CANCELING = EnumSet.of(OrderStatus.CANCELING);
    static final EventCause REFUND_COMPLETED = EventCause.system("REFUND_COMPLETED");

    private final OrderLedger ledger;
    private final OrderReader orderReader;
    private final TransactionTemplate writeTransaction;

    public RefundResults(OrderLedger ledger, OrderReader orderReader, PlatformTransactionManager transactionManager) {
        this.ledger = ledger;
        this.orderReader = orderReader;
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
            case REFUNDED -> refunded(order.id());
            case FAILED -> failed(order.id());
        }
    }

    private void refunded(Long orderId) {
        OrderTransition transition = writeTransaction.execute(status ->
                ledger.fire(orderId, OrderTrigger.REFUND_COMPLETED, CANCELING, REFUND_COMPLETED));
        if (!transition.applied() && transition.status() != OrderStatus.CANCELED) {
            log.error("환불이 끝났는데 주문이 취소 중이 아니다 — 확인 필요 orderId={} status={}", orderId, transition.status());
            return;
        }
        log.info("환불 결과 반영 orderId={} result=REFUNDED applied={} status={}", orderId, transition.applied(),
                transition.status());
    }

    private void failed(Long orderId) {
        if (Boolean.TRUE.equals(writeTransaction.execute(status -> ledger.noteRefundFailed(orderId)))) {
            log.warn("환불 결과 반영 — 환불 확정 실패, 주문은 취소 중에 남는다 orderId={}", orderId);
            return;
        }
        // 이미 남겼거나(같은 알림 재수신) 취소 중이 아니다 — 후자는 환불 요청 경로상 갈 수 없다
        log.info("환불 결과 반영 — 환불 실패 이력을 남기지 않음(이미 남겼거나 취소 중이 아님) orderId={}", orderId);
    }
}
