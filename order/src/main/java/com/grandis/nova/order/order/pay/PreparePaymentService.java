package com.grandis.nova.order.order.pay;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.client.payment.PaymentAttempt;
import com.grandis.nova.order.client.payment.PaymentPreparer;
import com.grandis.nova.order.client.preorder.PreorderPayability;
import com.grandis.nova.order.client.preorder.PreorderReader;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.enums.OrderTrigger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderItem;
import com.grandis.nova.order.order.domain.model.OrderTransition;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.order.vo.OrderToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 결제 준비: 결제창을 열 값(결제사 주문 번호 · 금액 · 주문명)을 돌려준다. 돈은 오가지 않고 결제사를 부르지 않는다.
 *
 * 순서: 주문 확인(본인 · 결제 대기) → 결제 가능 재확인(preorder, 트랜잭션 밖) → payment 에 거래 생성(트랜잭션 밖).
 *
 * - 결제 가능 여부(기한 · 취소)는 preorder 가 판정한다. 주문 생성 뒤 시간이 지났으므로 결제 때 다시 묻는다.
 * - 예약이 취소를 마쳤는데 미결제 주문이 남아 있으면(주문 생성과 예약 취소가 엇갈려 취소가 NO_ORDER 로 정리된 경우, R1)
 *   여기서 그 주문을 취소하고 거절한다. 예약은 이미 취소돼 있어 preorder 에 결과를 알리지 않는다(아웃박스 없음) — 늦게 온
 *   취소 요청 메시지는 CANCELED 를 보고 같은 결과를 다시 적는다.
 * - 금액은 주문의 저장된 총액이다. 사용자가 보낸 값을 받지 않는다.
 * - payment 는 주문 상태를 모른다. "결제 대기인가" 판정은 여기서 끝낸다. 이 판정과 payment 호출 사이에 주문이 바뀌어도
 *   버려진 PENDING 거래가 남을 뿐이다 — 승인 시작이 원장 전제(AWAITING_PAYMENT)로 다시 막는다.
 * - preorder · payment 호출 중에 DB 잠금을 쥐지 않도록 트랜잭션은 짧게 여기서 직접 연다. 바깥 트랜잭션 안에서 부르면
 *   원장 예외가 바깥 작업까지 rollback-only 로 만들므로 들어올 때 확인한다.
 */
@Service
public class PreparePaymentService {

    private static final Logger log = LoggerFactory.getLogger(PreparePaymentService.class);

    static final Set<OrderStatus> CANCELABLE_HERE = EnumSet.of(OrderStatus.AWAITING_PAYMENT);
    /**
     * 남은 미결제 주문(R1)을 취소할 때의 이력 사유. 예약 취소 메시지의 사유("PREORDER_CANCEL:" + 예약의 취소 이유)와 일부러 다르게 둔다 —
     * 결제 가능 확인 응답에는 예약의 취소 이유가 없어 그 모양을 채울 수 없고, 이력에서 메시지가 아닌 결제 시도가 정리한 것임이 보여야 한다.
     */
    static final String LEFTOVER_CANCEL_REASON = "PREORDER_CANCELED";

    private final OrderReader orderReader;
    private final OrderLedger ledger;
    private final PreorderReader preorderReader;
    private final PaymentPreparer paymentPreparer;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public PreparePaymentService(OrderReader orderReader, OrderLedger ledger, PreorderReader preorderReader,
                                 PaymentPreparer paymentPreparer, PlatformTransactionManager transactionManager) {
        this.orderReader = orderReader;
        this.ledger = ledger;
        this.preorderReader = preorderReader;
        this.paymentPreparer = paymentPreparer;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    /**
     * @param customerId   인증 주체의 회원 id
     * @param sessionToken 사용자가 보낸 액세스 토큰. preorder · payment 에 그대로 전달한다. 로그 · 예외에 싣지 않는다
     * @param orderToken   주문 공개 토큰. 형식이 틀리면 없는 주문과 같다
     * @throws BusinessException ORDER_NOT_FOUND(없음 · 남의 주문) · ORDER_NOT_PAYABLE(결제 대기 아님 · 0원) ·
     *                           PREORDER_NOT_PAYABLE · PAYMENT_WINDOW_EXPIRED · UNAUTHENTICATED · DEPENDENCY_UNAVAILABLE
     * @throws IllegalStateException 주문의 예약이 보이지 않거나 다른 예약 · 회원이다(데이터 어긋남), payment 연동 오류
     */
    public PreparedPayment prepare(Long customerId, String sessionToken, String orderToken) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("결제 준비는 트랜잭션 밖에서 불러야 한다 — 외부 호출 동안 잠금을 쥐지 않게");
        }
        OrderWithItems found = findPayable(customerId, orderToken);
        Order order = found.order();
        requirePayablePreorder(order, preorderReader.find(order.preorderToken(), sessionToken)
                .orElseThrow(() -> new IllegalStateException("주문의 예약이 보이지 않는다: orderId=" + order.id())));

        // 0원 주문은 결제창을 열 수 없다(payment 는 0원을 400 으로 거절한다 — 그대로 부르면 늘 500). 다시 불러도 같으므로 409.
        if (order.totalAmount().amount().signum() == 0) {
            throw new BusinessException(OrderErrorCode.ORDER_NOT_PAYABLE);
        }
        String orderName = OrderNames.of(found.items());
        PaymentAttempt attempt = paymentPreparer.openCapture(order.id(), order.totalAmount().amount(), sessionToken);
        return new PreparedPayment(attempt.providerOrderId(), order.totalAmount(), orderName);
    }

    /** 본인의 결제 대기 주문. 남의 주문은 존재를 숨긴다. */
    private OrderWithItems findPayable(Long customerId, String orderToken) {
        OrderWithItems found = readTransaction.execute(status -> parse(orderToken)
                .flatMap(orderReader::findByOrderToken)
                .filter(order -> order.customerId().equals(customerId))
                .map(order -> new OrderWithItems(order, orderReader.findItems(order.id()))))
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        if (found.order().status() != OrderStatus.AWAITING_PAYMENT) {
            throw new BusinessException(OrderErrorCode.ORDER_NOT_PAYABLE);
        }
        if (found.order().source() != OrderSource.PREORDER) {
            throw new IllegalStateException("사전예약 주문만 결제할 수 있다: orderId=" + found.order().id());
        }
        return found;
    }

    /**
     * 주문은 그 예약에서 만들었고 복합 FK(preorder_id, customer_id)로 묶여 있다 — 응답의 예약 · 회원이 다르면 데이터가 어긋난 것이라 500 이다.
     */
    private void requirePayablePreorder(Order order, PreorderPayability preorder) {
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

    private static Optional<OrderToken> parse(String orderToken) {
        try {
            return Optional.of(new OrderToken(orderToken));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private record OrderWithItems(Order order, List<OrderItem> items) {
    }
}
