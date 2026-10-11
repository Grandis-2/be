package com.grandis.nova.order.cart;

import com.grandis.nova.order.cart.domain.model.CartLine;
import com.grandis.nova.order.cart.domain.model.CartSlot;
import com.grandis.nova.order.cart.domain.repository.CartStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 장바구니 주문의 결제 성공 때 산 만큼 장바구니에서 뺀다(명세 — 주문 수량만큼 차감하고 0 이하면 삭제). 결제 결과 반영과 한 트랜잭션이다
 * (MANDATORY) — 결제 결과는 한 번만 반영되므로(원장의 승인 중 전제) 차감도 한 번이다.
 *
 * 그 회원의 줄을 잠근 뒤 판정한다({@link CartStore#lockByCustomer}). 주문 뒤 사용자가 지운 줄은 건너뛰고, 수량을 줄였으면 0 이하가 되어 지운다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class CartDeduction {

    private final CartStore store;
    private final Clock clock;

    public CartDeduction(CartStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** @param purchased 자리 → 산 수량 */
    public void deduct(UUID customerId, Map<CartSlot, Integer> purchased) {
        List<CartLine> lines = store.lockByCustomer(customerId);
        for (CartLine line : lines) {
            Integer bought = purchased.get(line.slot());
            if (bought == null) {
                continue;
            }
            int left = line.quantity() - bought;
            if (left <= 0) {
                if (store.delete(customerId, line.id()) != 1) {
                    throw new IllegalStateException("잠근 장바구니 줄을 지우지 못했다: lineId=" + line.id());
                }
            } else if (store.changeQuantity(line.id(), left, clock.instant()) != 1) {
                throw new IllegalStateException("잠근 장바구니 줄의 수량을 바꾸지 못했다: lineId=" + line.id());
            }
        }
    }
}
