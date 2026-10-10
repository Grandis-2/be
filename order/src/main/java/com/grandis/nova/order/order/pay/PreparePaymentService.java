package com.grandis.nova.order.order.pay;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.client.payment.PaymentAttempt;
import com.grandis.nova.order.client.payment.PayableTarget;
import com.grandis.nova.order.client.payment.PaymentPreparer;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderItem;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.vo.OrderToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 결제 준비: 결제창을 열 값(결제사 주문 번호 · 금액 · 주문명)을 돌려준다. 돈은 오가지 않고 결제사를 부르지 않는다.
 *
 * 순서: 주문 확인(본인 · 결제 대기) → 결제 가능 재확인({@link PayabilityGate}, 트랜잭션 밖) → payment 에 거래 생성(트랜잭션 밖).
 *
 * - 사전예약 · 장바구니 주문을 받는다. 결제할 수 있는지는 출처마다 다르게 판정한다({@link PayabilityGate}).
 * - 금액은 주문의 저장된 총액이다. 사용자가 보낸 값을 받지 않는다.
 * - payment 는 주문 상태를 모른다. "결제 대기인가" 판정은 여기서 끝낸다. 이 판정과 payment 호출 사이에 주문이 바뀌어도
 *   버려진 PENDING 거래가 남을 뿐이다 — 승인 시작이 원장 전제(AWAITING_PAYMENT)로 다시 막는다.
 * - preorder · payment 호출 중에 DB 잠금을 쥐지 않도록 트랜잭션은 짧게 연다. 바깥 트랜잭션 안에서 부르면
 *   원장 예외가 바깥 작업까지 rollback-only 로 만들므로 들어올 때 확인한다.
 */
@Service
public class PreparePaymentService {

    private final OrderReader orderReader;
    private final PayabilityGate payability;
    private final PaymentPreparer paymentPreparer;
    private final TransactionTemplate readTransaction;

    PreparePaymentService(OrderReader orderReader, PayabilityGate payability, PaymentPreparer paymentPreparer,
                          PlatformTransactionManager transactionManager) {
        this.orderReader = orderReader;
        this.payability = payability;
        this.paymentPreparer = paymentPreparer;
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
    public PreparedPayment prepare(UUID customerId, String sessionToken, String orderToken) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("결제 준비는 트랜잭션 밖에서 불러야 한다 — 외부 호출 동안 잠금을 쥐지 않게");
        }
        OrderWithItems found = findPayable(customerId, orderToken);
        Order order = found.order();
        payability.require(order, sessionToken);

        // 0원 주문은 결제창을 열 수 없다(payment 는 0원을 400 으로 거절한다 — 그대로 부르면 늘 500). 다시 불러도 같으므로 409.
        if (order.totalAmount().amount().signum() == 0) {
            throw new BusinessException(OrderErrorCode.ORDER_NOT_PAYABLE);
        }
        String orderName = OrderNames.of(found.items());
        PaymentAttempt attempt = paymentPreparer.openCapture(PayableTarget.of(order), sessionToken);
        return new PreparedPayment(attempt.providerOrderId(), order.totalAmount(), orderName);
    }

    /** 본인의 결제 대기 주문. 남의 주문은 존재를 숨긴다. */
    private OrderWithItems findPayable(UUID customerId, String orderToken) {
        OrderWithItems found = readTransaction.execute(status -> OrderToken.parse(orderToken)
                .flatMap(orderReader::findByOrderToken)
                .filter(order -> order.customerId().equals(customerId))
                .map(order -> new OrderWithItems(order, orderReader.findItems(order.id()))))
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        if (found.order().status() != OrderStatus.AWAITING_PAYMENT) {
            throw new BusinessException(OrderErrorCode.ORDER_NOT_PAYABLE);
        }
        return found;
    }

    private record OrderWithItems(Order order, List<OrderItem> items) {
    }
}
