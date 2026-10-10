package com.grandis.nova.order.order.pay;

import com.grandis.nova.order.cart.CartDeduction;
import com.grandis.nova.order.cart.domain.model.CartSlot;
import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderItem;
import com.grandis.nova.order.order.domain.model.OrderLine;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.stock.StockLedger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
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
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class CartOrderFulfillment {

    private final OrderReader orderReader;
    private final CartDeduction cart;
    private final StockLedger stock;

    CartOrderFulfillment(OrderReader orderReader, CartDeduction cart, StockLedger stock) {
        this.orderReader = orderReader;
        this.cart = cart;
        this.stock = stock;
    }

    void onApproved(UUID orderId) {
        Order order = orderReader.findById(orderId).orElseThrow(() -> new IllegalStateException("주문이 사라졌다: " + orderId));
        if (order.source() != OrderSource.CART) {
            return;
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
        stock.sell(sold);
    }
}
