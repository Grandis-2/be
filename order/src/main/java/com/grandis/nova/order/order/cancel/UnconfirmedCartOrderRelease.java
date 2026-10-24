package com.grandis.nova.order.order.cancel;

import com.grandis.nova.common.outbox.OutboxWriter;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderItem;
import com.grandis.nova.order.order.domain.model.OrderTransition;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.outbox.NotificationRequested;
import com.grandis.nova.order.stock.StockLedger;
import com.grandis.nova.order.stock.StockMismatches;
import com.grandis.nova.order.stock.domain.exception.StockReleaseMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 결제 결과를 끝내 모르는 승인 중 장바구니 · 바로 구매 주문을 취소하고, 확보한 재고를 돌려주고, 회원에게 결제 확인 실패를 알린다 — 셋이
 * 부르는 쪽의 트랜잭션 하나다(MANDATORY).
 *
 * 주문 행을 잠근 뒤 판정한다(원장). 승인 중일 때만 취소 + 반환 표식을 한 UPDATE 로 적고, 그때만 재고를 돌려주고 알린다 — 그래서 둘 다 한
 * 번이다. 그사이 결과가 왔으면(승인 · 거절) 아무것도 바꾸지 않는다. 취소한 뒤 승인이 늦게 오면 결과 반영이 자동 환불을 요청한다
 * ({@link com.grandis.nova.order.order.pay.PaymentResults}). 잠금 순서: 주문 행 → 재고 행.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class UnconfirmedCartOrderRelease {

    private static final Logger log = LoggerFactory.getLogger(UnconfirmedCartOrderRelease.class);

    private final OrderLedger ledger;
    private final OrderReader orderReader;
    private final StockLedger stock;
    private final StockMismatches mismatches;
    private final OutboxWriter outboxWriter;

    public UnconfirmedCartOrderRelease(OrderLedger ledger, OrderReader orderReader, StockLedger stock, StockMismatches mismatches,
                                       OutboxWriter outboxWriter) {
        this.ledger = ledger;
        this.orderReader = orderReader;
        this.stock = stock;
        this.mismatches = mismatches;
        this.outboxWriter = outboxWriter;
    }

    /** @throws IllegalStateException 반환할 확보가 모자란다 — 데이터 어긋남. 취소도 롤백된다 */
    public OrderTransition cancel(UUID orderId, EventCause cause) {
        OrderTransition canceled = ledger.cancelUnconfirmedReleasingStock(orderId, cause);
        if (!canceled.applied()) {
            return canceled;
        }
        Map<UUID, Integer> reserved = new HashMap<>();
        for (OrderItem item : orderReader.findItems(orderId)) {
            reserved.merge(item.line().optionId(), item.line().quantity().value(), Integer::sum);
        }
        try {
            stock.release(reserved);
        } catch (StockReleaseMismatchException e) {
            // 문구("재고 어긋남")는 다른 자리와 같아 로그를 한 번에 찾는 열쇠다 — 바꾸지 않는다
            log.error("재고 어긋남 — 결제 결과를 모른 주문의 확보를 반환하지 못해 취소하지 않았다, 재고 장부 확인 필요 orderId={} optionId={} quantity={}",
                    orderId, e.optionId(), e.quantity(), e);
            mismatches.record(StockMismatches.Site.UNCONFIRMED_CANCEL);
            throw new IllegalStateException("취소한 주문의 확보를 반환하지 못했다: orderId=" + orderId, e);
        }
        Order order = orderReader.findById(orderId).orElseThrow(() -> new IllegalStateException("주문이 사라졌다: " + orderId));
        outboxWriter.append(NotificationRequested.paymentUnconfirmed(orderId, order.customerId()));
        return canceled;
    }
}
