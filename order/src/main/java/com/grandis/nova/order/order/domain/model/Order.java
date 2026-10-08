package com.grandis.nova.order.order.domain.model;

import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.order.vo.Money;
import com.grandis.nova.order.order.vo.OrderToken;
import com.grandis.nova.order.order.vo.ShipTo;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 주문의 한 시점 스냅샷. 불변이다 — 상태는 이 객체를 고쳐서 바꾸지 않고 {@link com.grandis.nova.order.order.OrderLedger} 가
 * 저장소에서 "현재 상태를 조건으로 한 UPDATE + 이력 INSERT" 로 바꾼다. 그래서 읽어 둔 주문이 옛 상태를 되써 넣을 길이 없다.
 *
 * toString 은 식별 · 상태만 싣는다. 배송지 · 관리자 메모에는 개인정보가 들어갈 수 있어 로그 · 예외 메시지로 새지 않게.
 *
 * equals 는 값 비교다. 같은 주문의 전이 전 · 후 스냅샷은 서로 다르다 — 같은 주문인지는 id 로 판단한다.
 *
 * 항목({@link OrderItem}) · 이력({@link OrderEvent})은 들고 있지 않고 id 로 잇는다. 상태 전이는 항목이 필요 없고,
 * 조회는 필요한 것만 따로 읽는다.
 *
 * 생성자는 어느 경로로 만들든 지켜야 하는 규칙(DB CHECK 와 같다)을 검사한다. 새 주문을 받을지의 규칙은 {@link #place} 에 있다.
 *
 * @param id              저장 전이면 null
 * @param preorderToken   사전예약 주문이 가리키는 예약의 공개 UUID. 결제 때 preorder 에 다시 물을 때 쓴다. 아니면 null
 * @param authorizingProviderOrderId 승인을 기다리는 결제창(결제사 주문 번호). AUTHORIZING 일 때만 있다 — 거절 · 되돌림은 이 결제창의
 *                        것일 때만 반영한다(늦게 온 이전 결제창의 거절이 새 결제창의 승인 중에 주문을 되돌리지 않게)
 * @param paymentDueAt    일반 주문의 10분 기한. 사전예약 주문은 예약의 24시간 기한을 따르므로 늘 null
 * @param stockReleasedAt 일반 판매의 재고 반환 표식. 사전예약 주문은 늘 null
 * @param createdAt       저장 전이면 null
 * @param updatedAt       저장 전이면 null
 */
public record Order(
        UUID id,
        OrderToken orderToken,
        UUID customerId,
        OrderSource source,
        UUID preorderId,
        String preorderToken,
        OrderStatus status,
        String authorizingProviderOrderId,
        Money totalAmount,
        Instant paymentDueAt,
        Instant stockReleasedAt,
        ShipTo shipTo,
        String internalNote,
        long eventSequence,
        Instant createdAt,
        Instant updatedAt
) {

    public Order {
        Objects.requireNonNull(orderToken, "orderToken");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(totalAmount, "totalAmount");
        Objects.requireNonNull(shipTo, "shipTo");
        boolean preorder = source == OrderSource.PREORDER;
        // ck_order_preorder_link
        if (preorder != (preorderId != null)) {
            throw new IllegalArgumentException("사전예약 주문만 preorderId 를 가진다: source=" + source);
        }
        PreorderTokens.requireLinked(source, preorderToken);
        // ck_order_due
        if (preorder != (paymentDueAt == null)) {
            throw new IllegalArgumentException("결제 기한은 일반 주문에만 있다: source=" + source);
        }
        // ck_order_preorder_no_stock · ck_order_stock_released_canceled
        if (stockReleasedAt != null && (preorder || status != OrderStatus.CANCELED)) {
            throw new IllegalArgumentException("재고 반환 표식은 취소된 일반 주문에만 있다");
        }
        // ck_order_authorizing_attempt
        if ((status == OrderStatus.AUTHORIZING) != (authorizingProviderOrderId != null)) {
            throw new IllegalArgumentException("승인 중인 주문만 결제창 번호를 가진다: status=" + status);
        }
        if (eventSequence < OrderEvent.FIRST_SEQUENCE) {
            throw new IllegalArgumentException("이력 번호는 1 이상이다: " + eventSequence);
        }
    }

    /**
     * 새 주문(저장 전, id 없음). 결제 대기에서 시작하고 이력 번호는 첫 이력과 같은 1이다.
     *
     * 지금은 사전예약 주문만 받는다 — 일반 주문은 결제 기한(10분) · 재고 규칙이 아직 없다.
     * 사전예약 주문은 예약 하나에 옵션 하나 · 수량 1이다(예약에 수량 칸이 없다).
     */
    public static Order place(OrderDraft draft, OrderToken orderToken) {
        if (draft.source() != OrderSource.PREORDER) {
            throw new IllegalArgumentException("사전예약 주문만 만들 수 있다: source=" + draft.source());
        }
        if (draft.lines().size() != 1 || draft.lines().getFirst().quantity().value() != 1) {
            throw new IllegalArgumentException("사전예약 주문은 옵션 하나 · 수량 1이다");
        }
        return new Order(null, orderToken, draft.customerId(), draft.source(), draft.preorderId(),
                draft.preorderToken(), OrderStatus.AWAITING_PAYMENT, null, draft.totalAmount(), null, null, draft.shipTo(), null,
                OrderEvent.FIRST_SEQUENCE, null, null);
    }

    @Override
    public String toString() {
        return "Order[id=%s, source=%s, preorderId=%s, status=%s, eventSequence=%d]"
                .formatted(id, source, preorderId, status, eventSequence);
    }

    /** 저장 전인가. 저장소는 이런 주문만 새로 넣는다. */
    public boolean isNew() {
        return id == null;
    }

    /** 취소를 받아들일 수 있는가. 규칙은 {@link OrderStatus#isCancelable()} 에 있다. */
    public boolean isCancelable() {
        return status.isCancelable();
    }
}
