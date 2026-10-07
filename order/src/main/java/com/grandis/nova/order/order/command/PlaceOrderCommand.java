package com.grandis.nova.order.order.command;

import com.grandis.nova.order.order.domain.enums.OrderSource;
import com.grandis.nova.order.order.domain.model.OrderDraft;
import com.grandis.nova.order.order.domain.model.OrderLine;
import com.grandis.nova.order.order.vo.Money;
import com.grandis.nova.order.order.vo.Quantity;
import com.grandis.nova.order.order.vo.ShipTo;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 주문 생성 입력. 유스케이스가 요청 · 외부 조회 결과를 모아 만든다. 값만 옮기고 규칙은 모른다 —
 * {@link #toDraft()} 가 VO · 도메인으로 바꾸면서 검사한다. 금액 칸이 없다.
 *
 * @param preorderId    사전예약 주문이면 그 예약의 내부 id
 * @param preorderToken 사전예약 주문이면 그 예약의 공개 UUID
 */
public record PlaceOrderCommand(
        UUID customerId,
        OrderSource source,
        UUID preorderId,
        String preorderToken,
        Address shipTo,
        List<Line> lines
) {

    public OrderDraft toDraft() {
        return new OrderDraft(customerId, source, preorderId, preorderToken, shipTo.toShipTo(),
                lines.stream().map(Line::toOrderLine).toList());
    }

    /** 배송지. 개인정보라 toString 에 값을 싣지 않는다 — 명령을 로그에 찍어도 새지 않게(ShipTo 와 같다). */
    public record Address(String name, String phone, String postalCode, String line1, String line2) {

        @Override
        public String toString() {
            return "Address[***]";
        }

        ShipTo toShipTo() {
            return new ShipTo(name, phone, postalCode, line1, line2);
        }
    }

    /** 가격 · 이름은 예약 접수 시점의 스냅샷을 그대로 옮긴다. */
    public record Line(UUID productId, UUID optionId, int quantity, BigDecimal unitPrice,
                       String productTitle, String optionTitle) {

        OrderLine toOrderLine() {
            return new OrderLine(productId, optionId, new Quantity(quantity), new Money(unitPrice),
                    productTitle, optionTitle);
        }
    }
}
