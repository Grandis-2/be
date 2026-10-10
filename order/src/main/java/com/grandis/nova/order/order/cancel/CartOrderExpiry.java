package com.grandis.nova.order.order.cancel;

import com.grandis.nova.order.MySqlLockFailures;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderTransition;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.vo.EventCause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 결제 기한(10분)이 지난 결제 대기 장바구니 주문을 취소하고 재고를 돌려준다 — 결제창을 그냥 닫은 주문의 확보를 푼다(명세).
 *
 * 주기마다 기한 <= 지금인 결제 대기 주문을 기한 이른 순으로 {@link #BATCH} 개씩 고르고(잠그지 않음), 주문마다 트랜잭션 하나에서 잠근 뒤 다시
 * 판정한다({@link UnpaidCartOrderRelease}) — 그 사이 결제를 시작했으면(승인 중) 건드리지 않는다. 승인 중은 결과를 받으므로 고르지도 않는다.
 * 인스턴스가 여럿이어도 같다 — 늦게 잠근 쪽은 이미 취소된 주문을 보고 지나간다(반환은 한 번).
 *
 * 한 주문의 실패(교착 · 잠금 대기 초과 · 데이터 어긋남)는 그 주문만 건너뛰고 다음 주기에 다시 고른다.
 */
@Component
public class CartOrderExpiry {

    private static final Logger log = LoggerFactory.getLogger(CartOrderExpiry.class);

    static final int BATCH = 100;
    static final String EXPIRED_REASON = "PAYMENT_EXPIRED";

    private final OrderReader orderReader;
    private final UnpaidCartOrderRelease release;
    private final TransactionTemplate writeTransaction;
    private final Clock clock;

    public CartOrderExpiry(OrderReader orderReader, UnpaidCartOrderRelease release, PlatformTransactionManager transactionManager,
                           Clock clock) {
        this.orderReader = orderReader;
        this.release = release;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${nova.cart-order.expiry-interval:30s}", initialDelayString = "${nova.cart-order.expiry-interval:30s}")
    void run() {
        int expired = expireDue(clock.instant());
        if (expired > 0) {
            log.info("결제 기한이 지난 장바구니 주문 {}건 취소 · 재고 반환", expired);
        }
    }

    /**
     * @param now 기준 시각 — 기한 <= now 인 주문이 대상이다({@link Order#acceptsPaymentAt} 의 반대)
     * @return 이번에 취소한 주문 수
     */
    int expireDue(Instant now) {
        List<Order> due = orderReader.findExpiredCartOrders(now, BATCH);
        int expired = 0;
        for (Order order : due) {
            try {
                OrderTransition transition = writeTransaction.execute(status -> release.cancel(order.id(), EventCause.system(EXPIRED_REASON)));
                if (transition.applied()) {
                    expired++;
                }
            } catch (PessimisticLockingFailureException e) {
                log.warn("결제 기한 만료 처리 잠금 실패(교착={}), 다음 주기에 다시 orderId={}", MySqlLockFailures.isDeadlock(e), order.id(), e);
            } catch (RuntimeException e) {
                log.error("결제 기한 만료 처리 실패, 다음 주기에 다시 orderId={}", order.id(), e);
            }
        }
        return expired;
    }
}
