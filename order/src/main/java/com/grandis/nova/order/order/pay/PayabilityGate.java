package com.grandis.nova.order.order.pay;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.client.preorder.PreorderPayability;
import com.grandis.nova.order.client.preorder.PreorderReader;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.enums.OrderTrigger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderTransition;
import com.grandis.nova.order.order.vo.EventCause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;

/**
 * 결제 가능 재확인(B1) — 결제 준비 · 승인이 함께 쓴다.
 *
 * 장바구니 주문은 주문의 기한(만든 때 + 10분)만 본다 — 기한이 지났으면 PAYMENT_WINDOW_EXPIRED. 기한이 지난 결제 대기 주문의 취소 · 재고 반환은
 * 결제 기한 만료 처리(별도)가 한다. 승인 중이 된 뒤에는 기한을 넘겨도 결과를 받는다 — 만료 처리는 결제 대기만 고른다.
 *
 * 사전예약 주문의 기한 · 취소 판정은 preorder 한 곳이 한다(D3). 주문 생성 뒤 시간이 지났으므로
 * 결제 때 다시 묻는다. 트랜잭션 밖에서 부른다(preorder 응답을 기다리는 동안 잠금을 쥐지 않게).
 *
 * 예약이 취소를 마쳤는데 미결제 주문이 남아 있으면(주문 생성과 예약 취소가 엇갈려 취소가 NO_ORDER 로 정리된 경우, R1)
 * 그 주문을 취소하고 거절한다. 예약은 이미 취소돼 있어 preorder 에 결과를 알리지 않는다(아웃박스 없음) — 늦게 온 취소 요청
 * 메시지는 CANCELED 를 보고 같은 결과를 다시 적는다.
 */
@Component
class PayabilityGate {

    private static final Logger log = LoggerFactory.getLogger(PayabilityGate.class);

    static final Set<OrderStatus> CANCELABLE_HERE = EnumSet.of(OrderStatus.AWAITING_PAYMENT);
    /**
     * 남은 미결제 주문(R1)을 취소할 때의 이력 사유. 예약 취소 메시지의 사유("PREORDER_CANCEL:" + 예약의 취소 이유)와 일부러 다르게 둔다 —
     * 결제 가능 확인 응답에는 예약의 취소 이유가 없어 그 모양을 채울 수 없고, 이력에서 메시지가 아닌 결제 시도가 정리한 것임이 보여야 한다.
     */
    static final String LEFTOVER_CANCEL_REASON = "PREORDER_CANCELED";

    private final PreorderReader preorderReader;
    private final OrderLedger ledger;
    private final TransactionTemplate writeTransaction;
    private final Clock clock;

    PayabilityGate(PreorderReader preorderReader, OrderLedger ledger, PlatformTransactionManager transactionManager, Clock clock) {
        this.preorderReader = preorderReader;
        this.ledger = ledger;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * @param sessionToken 사용자가 보낸 액세스 토큰. preorder 에 그대로 전달한다
     * @throws BusinessException     PREORDER_NOT_PAYABLE · PAYMENT_WINDOW_EXPIRED · UNAUTHENTICATED · DEPENDENCY_UNAVAILABLE
     * @throws IllegalStateException 주문의 예약이 보이지 않거나 다른 예약 · 회원이다(데이터 어긋남), 바로 구매 주문(아직 없음)
     */
    void require(Order order, String sessionToken) {
        switch (order.source()) {
            case PREORDER -> requirePreorderPayable(order, sessionToken);
            case CART -> {
                if (!order.acceptsPaymentAt(clock.instant())) {
                    throw new BusinessException(OrderErrorCode.PAYMENT_WINDOW_EXPIRED);
                }
            }
            case BUY_NOW -> throw new IllegalStateException("바로 구매 주문은 아직 없다: orderId=" + order.id());
        }
    }

    private void requirePreorderPayable(Order order, String sessionToken) {
        PreorderPayability preorder = preorderReader.find(order.preorderToken(), sessionToken)
                .orElseThrow(() -> new IllegalStateException("주문의 예약이 보이지 않는다: orderId=" + order.id()));
        // 주문은 그 예약에서 만들었고 복합 FK(preorder_id, customer_id)로 묶여 있다 — 다르면 데이터가 어긋난 것이라 500 이다
        if (!preorder.preorderInternalId().equals(order.preorderId()) || !preorder.isOwnedBy(order.customerId())) {
            throw new IllegalStateException("주문과 다른 예약 · 회원의 응답이다: orderId=" + order.id());
        }
        if (preorder.isCanceled()) {
            cancelLeftover(order);
            throw new BusinessException(OrderErrorCode.PREORDER_NOT_PAYABLE);
        }
        if (preorder.isDuePassed()) {
            throw new BusinessException(OrderErrorCode.PAYMENT_WINDOW_EXPIRED);
        }
        if (!preorder.payable()) {
            throw new BusinessException(OrderErrorCode.PREORDER_NOT_PAYABLE);
        }
    }

    /** 미결제일 때만 취소한다(원장이 잠근 뒤 판정). 그사이 승인이 시작됐으면 바꾸지 않는다 — 거절 응답은 같다. */
    private void cancelLeftover(Order order) {
        EventCause cause = EventCause.system(LEFTOVER_CANCEL_REASON);
        OrderTransition transition = writeTransaction.execute(status ->
                ledger.fire(order.id(), OrderTrigger.CANCEL_REQUESTED, CANCELABLE_HERE, cause));
        log.info("취소된 예약의 미결제 주문 정리 orderId={} applied={} status={}",
                order.id(), transition.applied(), transition.status());
    }
}
