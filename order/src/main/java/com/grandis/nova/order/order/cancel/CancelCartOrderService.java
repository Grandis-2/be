package com.grandis.nova.order.order.cancel;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.order.MySqlLockFailures;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderTransition;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.order.vo.OrderToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * 사용자의 미결제 장바구니 주문 취소(명세 POST /orders/{orderId}/cancel). 결제 대기면 그 자리에서 취소하고 재고를 돌려준다(200).
 * 다시 불러도 같은 결과다 — 이미 취소된 주문이면 200 CANCELED 이고 두 번 반환하지 않는다({@link UnpaidCartOrderRelease}).
 *
 * - 승인 중(결제 결과를 기다리는 중)이면 409 PAYMENT_IN_PROGRESS — 결과가 나면 다시 시도한다.
 * - 결제된 일반 주문의 취소 · 환불은 아직 없다 — 409 STATE_CONFLICT.
 * - 사전예약 주문은 예약 취소(preorder)로 취소한다 — 409 STATE_CONFLICT.
 * - 장바구니는 건드리지 않는다(설계 — 미결제 취소는 장바구니를 그대로 둔다).
 * - 교착(1213)이면 새 트랜잭션에서 한 번 더, 끝내 교착이거나 잠금 대기 초과(1205)면 503.
 */
@Service
public class CancelCartOrderService {

    private static final Logger log = LoggerFactory.getLogger(CancelCartOrderService.class);

    static final int MAX_ATTEMPTS = 2;

    private final OrderReader orderReader;
    private final UnpaidCartOrderRelease release;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public CancelCartOrderService(OrderReader orderReader, UnpaidCartOrderRelease release, PlatformTransactionManager transactionManager) {
        this.orderReader = orderReader;
        this.release = release;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    /**
     * @return 취소 뒤 상태(CANCELED)
     * @throws BusinessException 404 ORDER_NOT_FOUND(없음 · 남의 주문) · 409 PAYMENT_IN_PROGRESS · STATE_CONFLICT · 503
     */
    public Order cancel(UUID customerId, String orderToken) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("주문 취소는 트랜잭션 밖에서 불러야 한다 — 다시 하기가 새 트랜잭션이어야 한다");
        }
        Order order = readTransaction.execute(status -> OrderToken.parse(orderToken).flatMap(orderReader::findByOrderToken)
                        .filter(found -> found.customerId().equals(customerId)))
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        if (order.source() != OrderSource.CART) {
            throw new BusinessException(OrderErrorCode.STATE_CONFLICT, "사전예약 주문은 예약 취소로 취소합니다.");
        }
        OrderTransition transition = cancelWithRetry(order.id());
        if (transition.applied() || transition.status() == OrderStatus.CANCELED) {
            return readTransaction.execute(status -> orderReader.findById(order.id())).orElseThrow();
        }
        if (transition.status() == OrderStatus.AUTHORIZING) {
            throw new BusinessException(OrderErrorCode.PAYMENT_IN_PROGRESS);
        }
        throw new BusinessException(OrderErrorCode.STATE_CONFLICT, "결제된 주문은 아직 취소할 수 없습니다.");
    }

    private OrderTransition cancelWithRetry(UUID orderId) {
        for (int attempt = 1; ; attempt++) {
            try {
                return writeTransaction.execute(status -> release.cancel(orderId, EventCause.user()));
            } catch (PessimisticLockingFailureException e) {
                if (!MySqlLockFailures.isDeadlock(e)) {
                    log.warn("주문 취소 잠금 대기 초과, 다시 하지 않음 orderId={}", orderId, e);
                    throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
                }
                if (attempt >= MAX_ATTEMPTS) {
                    log.warn("주문 취소 교착 {}회, 포기 orderId={}", MAX_ATTEMPTS, orderId, e);
                    throw new BusinessException(CommonErrorCode.DEPENDENCY_UNAVAILABLE);
                }
                log.info("주문 취소 교착, 다시 시도 {}/{} orderId={}", attempt, MAX_ATTEMPTS, orderId);
            }
        }
    }
}
