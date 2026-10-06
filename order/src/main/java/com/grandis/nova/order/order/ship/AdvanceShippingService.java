package com.grandis.nova.order.order.ship;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.order.OrderErrorCode;
import com.grandis.nova.order.order.OrderLedger;
import com.grandis.nova.order.order.domain.model.Order;
import com.grandis.nova.order.order.domain.model.OrderTransition;
import com.grandis.nova.order.order.domain.repository.OrderReader;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.order.vo.OrderToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Map;

/**
 * 관리자 배송 단계 전이. 주문 하나를 한 단계 옮긴다 — 결제 · payment 와 무관한 order 안의 전이다.
 *
 * 한 트랜잭션: 토큰으로 주문 조회 → 원장 fire(전제 = 단계의 출발 상태) → 돌려받은 상태로 응답 판정.
 * 이미 그 단계면 바꾸지 않고 성공으로 답한다(응답 유실 뒤 재요청 · 두 번 누름). 그 밖에 옮길 수 없으면 409 와 지금 상태다.
 * 사유(EventCause)는 HTTP 경계에서 만들어 온다 — 원장 안에서 검증에 실패하면 트랜잭션이 rollback-only 가 된다.
 */
@Service
public class AdvanceShippingService {

    private final OrderReader orderReader;
    private final OrderLedger ledger;

    public AdvanceShippingService(OrderReader orderReader, OrderLedger ledger) {
        this.orderReader = orderReader;
        this.ledger = ledger;
    }

    /** @throws BusinessException ORDER_NOT_FOUND · ORDER_STEP_NOT_ALLOWED(details.status = 지금 상태) */
    @Transactional
    public ShippingAdvance advance(String orderToken, ShippingStep step, EventCause cause) {
        Order order = OrderToken.parse(orderToken).flatMap(orderReader::findByOrderToken)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        OrderTransition transition = ledger.fire(order.id(), step.trigger(), EnumSet.of(step.from()), cause);
        if (!transition.applied() && transition.status() != step.to()) {
            throw new BusinessException(OrderErrorCode.ORDER_STEP_NOT_ALLOWED,
                    Map.of("status", transition.status().name()));
        }
        return new ShippingAdvance(order.orderToken(), transition.status(), transition.applied());
    }
}
