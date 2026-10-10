package com.grandis.nova.order.order.cancel;

import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.model.OrderItem;
import com.grandis.nova.order.order.domain.model.OrderTransition;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.stock.StockLedger;
import com.grandis.nova.order.stock.domain.exception.StockReleaseMismatchException;
import com.grandis.nova.order.stock.domain.repository.StockReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 결제 대기인 장바구니 주문을 취소하고 확보한 재고를 돌려준다 — 사용자 취소와 결제 기한 만료가 함께 쓴다. 부르는 쪽의 트랜잭션에서 한다(MANDATORY).
 *
 * 주문 행을 잠근 뒤 판정한다(원장). 결제 대기일 때만 취소 + 반환 표식을 한 UPDATE 로 적고, 그때만 재고를 반환한다 — 그래서 반환은 한 번이다
 * (사용자 취소 · 만료 · 새 장바구니 주문의 앞 주문 취소가 겹쳐도). 결제 대기가 아니면 아무것도 바꾸지 않고 지금 상태를 돌려준다.
 * 잠금 순서: 주문 행 → 재고 행.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class UnpaidCartOrderRelease {

    private static final Logger log = LoggerFactory.getLogger(UnpaidCartOrderRelease.class);

    private final OrderLedger ledger;
    private final OrderReader orderReader;
    private final StockLedger stock;
    private final StockReader stockReader;

    public UnpaidCartOrderRelease(OrderLedger ledger, OrderReader orderReader, StockLedger stock, StockReader stockReader) {
        this.ledger = ledger;
        this.orderReader = orderReader;
        this.stock = stock;
        this.stockReader = stockReader;
    }

    /** @throws IllegalStateException 반환할 확보가 모자란다 — 데이터 어긋남. 취소도 롤백된다 */
    public OrderTransition cancel(UUID orderId, EventCause cause) {
        OrderTransition canceled = ledger.cancelUnpaidReleasingStock(orderId, cause);
        if (canceled.applied()) {
            Map<UUID, Integer> reserved = new HashMap<>();
            for (OrderItem item : orderReader.findItems(orderId)) {
                reserved.merge(item.line().optionId(), item.line().quantity().value(), Integer::sum);
            }
            try {
                stock.release(reserved);
            } catch (StockReleaseMismatchException e) {
                // 경보 규칙이 이 문구("재고 어긋남")로 잡는다 — 결제 반영 · 장바구니 주문 생성의 같은 경보와 같은 문구다. 바꾸지 않는다.
                // 싣는 것은 걸린 옵션 하나뿐이다 — 앞 옵션들은 이미 뺐다가 롤백되므로 그 숫자는 복구에 쓸 수 없다. 걸린 옵션은 UPDATE 가 0행이라 바뀌지 않았다
                log.error("재고 어긋남 — 결제 안 된 장바구니 주문의 확보를 반환하지 못해 취소하지 않았다, 재고 장부 확인 필요 orderId={} optionId={} quantity={} reservedNow={}",
                        orderId, e.optionId(), e.quantity(), reservedOf(e.optionId()), e);
                throw new IllegalStateException("취소한 주문의 확보를 반환하지 못했다: orderId=" + orderId, e);
            }
        }
        return canceled;
    }

    /** 경보에 싣는 그때의 확보. 읽다 실패해도 경보와 원래 예외를 잃지 않는다. */
    private Object reservedOf(UUID optionId) {
        try {
            return stockReader.findByOptionIds(List.of(optionId)).stream().map(level -> (Object) level.reserved()).findFirst().orElse("재고 행 없음");
        } catch (RuntimeException e) {
            return "읽지 못함(" + e.getClass().getSimpleName() + ")";
        }
    }
}
