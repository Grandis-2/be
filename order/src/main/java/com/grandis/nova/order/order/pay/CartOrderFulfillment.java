package com.grandis.nova.order.order.pay;

import com.grandis.nova.order.cart.CartDeduction;
import com.grandis.nova.order.cart.domain.model.CartSlot;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderItem;
import com.grandis.nova.order.order.domain.model.OrderLine;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.stock.StockLedger;
import com.grandis.nova.order.stock.domain.repository.StockReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 장바구니 주문의 결제 승인을 반영한 그 트랜잭션에서(MANDATORY) 판매를 확정하고 장바구니에서 산 만큼 뺀다. 결제 결과는 원장의 승인 중 전제로
 * 한 번만 반영되므로({@link com.grandis.nova.order.order.OrderLedger#settlePayment}) 이것도 한 번이다 — 승인 응답과 결과 이벤트가 겹쳐도.
 *
 * 잠금 순서: 주문 행(원장) → 장바구니 줄 → 재고 행. 같은 회원의 장바구니 주문 생성은 장바구니 줄 → 앞 주문 행 → 재고 행이라 이 순간 겹치면
 * 교착할 수 있다 — 결제 결과 반영({@link PaymentResults})과 주문 생성 모두 교착이면 새 트랜잭션에서 다시 한다(시험으로 고정).
 * 사전예약 주문은 재고 · 장바구니가 없어 아무것도 하지 않는다.
 *
 * 판매 확정에서 재고 장부가 어긋나 있으면(확보가 모자람) 그 옵션은 옮기지 않고 전용 ERROR 로 알린다 — 돈은 나갔으니 주문은 결제됨으로,
 * 장바구니도 뺀다(2026-10-10 결정, 돈을 따른다). 운영자는 그 로그의 주문 · 옵션으로 재고 장부를 고친다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class CartOrderFulfillment {

    private static final Logger log = LoggerFactory.getLogger(CartOrderFulfillment.class);

    private final OrderReader orderReader;
    private final CartDeduction cart;
    private final StockLedger stock;
    private final StockReader stockReader;

    CartOrderFulfillment(OrderReader orderReader, CartDeduction cart, StockLedger stock, StockReader stockReader) {
        this.orderReader = orderReader;
        this.cart = cart;
        this.stock = stock;
        this.stockReader = stockReader;
    }

    /** @param providerOrderId 승인된 결제창 — 재고 어긋남 경보에 싣는다(운영자가 결제와 맞춰 볼 수 있게) */
    void onApproved(UUID orderId, String providerOrderId) {
        Order order = orderReader.findById(orderId).orElseThrow(() -> new IllegalStateException("주문이 사라졌다: " + orderId));
        switch (order.source()) {
            case PREORDER -> {
                return;
            }
            case CART -> {
            }
            // 지금은 오지 않는다(Order.place 가 바로 구매를 받지 않는다). 바로 구매가 생기면 여기서 판매 확정을 붙인다 — 이대로 두면 승인 반영이
            // 롤백돼 승인 중으로 굳는다
            case BUY_NOW -> throw new IllegalStateException("바로 구매 주문의 판매 확정은 아직 없다: orderId=" + orderId);
        }
        List<OrderItem> items = orderReader.findItems(orderId);
        Map<CartSlot, Integer> purchased = new HashMap<>();
        Map<UUID, Integer> sold = new HashMap<>();
        for (OrderItem item : items) {
            OrderLine line = item.line();
            int plain = line.quantity().value() - line.warrantyQuantity();
            if (plain > 0) {
                purchased.put(new CartSlot(line.optionId(), false), plain);
            }
            if (line.warrantyQuantity() > 0) {
                purchased.put(new CartSlot(line.optionId(), true), line.warrantyQuantity());
            }
            sold.put(line.optionId(), line.quantity().value());
        }
        cart.deduct(order.customerId(), purchased);
        List<UUID> mismatched = stock.sell(sold);
        if (!mismatched.isEmpty()) {
            // 경보 규칙이 이 문구("재고 어긋남")로 잡는다 — 바꾸지 않는다. 복구에 필요한 것: 판매로 옮기지 못한 수량과 그때의 확보
            Map<UUID, Integer> unsold = new LinkedHashMap<>();
            mismatched.forEach(option -> unsold.put(option, sold.get(option)));
            Map<UUID, Integer> reservedNow = new LinkedHashMap<>();
            stockReader.findByOptionIds(mismatched).forEach(level -> reservedNow.put(level.optionId(), level.reserved()));
            log.error("재고 어긋남 — 결제는 반영했지만 판매 확정을 못 했다, 재고 장부 확인 필요 orderId={} providerOrderId={} unsold={} reservedNow={}",
                    orderId, providerOrderId, unsold, reservedNow);
        }
    }
}
