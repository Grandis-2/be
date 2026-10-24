package com.grandis.nova.order.order.cancel;

import com.grandis.nova.order.MySqlLockFailures;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.repository.ExpiredOrderPosition;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.stock.domain.exception.StockReleaseMismatchException;
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
 * 만든 뒤 정한 시간(기본 20분)이 지나도 결제 결과를 몰라 승인 중에 멈춘 장바구니 · 바로 구매 주문을 취소하고 재고를 돌려준다. 지금까지는
 * 결과가 올 때까지 기다렸다 — 결제사 장애가 길면 주문은 "결제 확인 중" 에, 재고는 확보에 묶인 채였다. 사전예약 주문은 고르지 않는다 —
 * 대상당 진행 중 결제가 하나라(payment uq_payment_tx_active) 결과가 나기 전엔 다시 결제할 수 없으므로 결과를 기다린다.
 *
 * 고르기 · 이어 읽기 · 주문마다 트랜잭션 하나 · 실패한 주문만 건너뛰기는 {@link CartOrderExpiry} 와 같다. 잠근 뒤 다시 판정하므로 그사이 결과가
 * 왔으면 건드리지 않는다({@link UnconfirmedCartOrderRelease}). 취소한 뒤 승인이 오면 결과 반영이 자동 환불을 요청한다.
 * 꺼져 있으면({@link UnconfirmedPaymentProperties#enabled}) 아무것도 하지 않는다.
 */
@Component
public class UnconfirmedCartOrderExpiry {

    private static final Logger log = LoggerFactory.getLogger(UnconfirmedCartOrderExpiry.class);

    static final int BATCH = 100;
    static final String UNCONFIRMED_REASON = "PAYMENT_UNCONFIRMED";

    private final OrderReader orderReader;
    private final UnconfirmedCartOrderRelease release;
    private final UnconfirmedPaymentProperties properties;
    private final TransactionTemplate writeTransaction;
    private final Clock clock;

    public UnconfirmedCartOrderExpiry(OrderReader orderReader, UnconfirmedCartOrderRelease release,
                                      UnconfirmedPaymentProperties properties, PlatformTransactionManager transactionManager,
                                      Clock clock) {
        this.orderReader = orderReader;
        this.release = release;
        this.properties = properties;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${nova.order.payment-unconfirmed.interval:30s}",
            initialDelayString = "${nova.order.payment-unconfirmed.interval:30s}")
    void run() {
        if (!properties.enabled()) {
            return;
        }
        int canceled = cancelDue(clock.instant());
        if (canceled > 0) {
            log.warn("결제 결과를 모른 주문 {}건 취소 · 재고 반환", canceled);
        }
    }

    /**
     * @param now 기준 시각 — 만든 뒤 {@link UnconfirmedPaymentProperties#after} 가 지난 승인 중 주문이 대상이다
     * @return 이번에 취소한 주문 수
     */
    int cancelDue(Instant now) {
        return cancelDue(now, BATCH);
    }

    /** @param batch 한 쪽의 크기 — 시험이 이어 읽기를 작은 쪽으로 태운다 */
    int cancelDue(Instant now, int batch) {
        Instant dueBefore = properties.dueBefore(now);
        ExpiredOrderPosition after = ExpiredOrderPosition.START;
        int canceled = 0;
        while (true) {
            List<Order> page = orderReader.findUnconfirmedAuthorizingOrders(dueBefore, after, batch);
            for (Order order : page) {
                if (cancel(order)) {
                    canceled++;
                }
            }
            if (page.size() < batch) {
                return canceled;
            }
            Order last = page.getLast();
            after = new ExpiredOrderPosition(last.paymentDueAt(), last.id());
        }
    }

    private boolean cancel(Order order) {
        try {
            return writeTransaction.execute(status ->
                    release.cancel(order.id(), EventCause.system(UNCONFIRMED_REASON))).applied();
        } catch (PessimisticLockingFailureException e) {
            log.warn("결제 결과를 모른 주문 취소 잠금 실패(교착={}), 다음 주기에 다시 orderId={}", MySqlLockFailures.isDeadlock(e), order.id(), e);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof StockReleaseMismatchException) {
                log.warn("결제 결과를 모른 주문 취소 건너뜀 — 재고 어긋남, 다음 주기에 다시 orderId={}", order.id());
            } else {
                log.error("결제 결과를 모른 주문 취소 실패, 다음 주기에 다시 orderId={}", order.id(), e);
            }
        }
        return false;
    }
}
