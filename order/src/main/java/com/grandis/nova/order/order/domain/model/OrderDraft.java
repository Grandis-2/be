package com.grandis.nova.order.order.domain.model;

import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.vo.Money;
import com.grandis.nova.order.order.vo.ShipTo;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 아직 저장하지 않은 주문. 금액 칸이 없다 — 받은 금액을 믿지 않도록 {@link #totalAmount()} 로만 계산한다.
 *
 * 여기서 거르는 것은 어느 출처든 지켜야 하는 규칙이다(ck_order_preorder_link · uq_order_item_option 과 같다).
 * 지금 어떤 출처를 받아 줄지는 {@link Order#place} 가 정한다.
 *
 * @param preorderId    사전예약 주문이면 그 예약의 내부 id, 아니면 null
 * @param preorderToken 사전예약 주문이면 그 예약의 공개 UUID(preorder_id 와 같은 예약), 아니면 null
 */
public record OrderDraft(
        Long customerId,
        OrderSource source,
        Long preorderId,
        String preorderToken,
        ShipTo shipTo,
        List<OrderLine> lines
) {

    public OrderDraft {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(shipTo, "shipTo");
        Objects.requireNonNull(lines, "lines");
        lines = List.copyOf(lines);
        if ((source == OrderSource.PREORDER) != (preorderId != null)) {
            throw new IllegalArgumentException("사전예약 주문만 preorderId 를 가진다: source=" + source);
        }
        PreorderTokens.requireLinked(source, preorderToken);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("주문상품이 없다");
        }
        Set<Long> optionIds = new HashSet<>();
        for (OrderLine line : lines) {
            if (!optionIds.add(line.optionId())) {
                throw new IllegalArgumentException("같은 옵션이 두 번 들어 있다: optionId=" + line.optionId());
            }
        }
    }

    /** 주문 금액. 줄마다 단가 × 수량의 합이다. */
    public Money totalAmount() {
        return lines.stream().map(OrderLine::subtotal).reduce(Money.ZERO, Money::plus);
    }
}
