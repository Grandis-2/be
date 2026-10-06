package com.grandis.nova.order.order.cancel;

import com.grandis.nova.order.order.domain.enums.OrderStatus;
import com.grandis.nova.order.outbox.PreorderOrderSettled;

/** 예약 취소 수신의 처리 결과. 결과를 적었거나, 아직 정할 수 없어 나중에 다시 받아야 한다. */
public sealed interface CancelSettlement {

    /** 정리 결과를 아웃박스에 적었다. */
    record Settled(PreorderOrderSettled result) implements CancelSettlement {
    }

    /**
     * 지금은 결과를 정할 수 없다 — 승인 결과를 기다리거나(AUTHORIZING) 환불이 진행 중 · 확정 실패로 사람을 기다린다(CANCELING).
     * 결과를 적지 않았고, 이번에 취소 중으로 바꿨다면 그 전이와 환불 요청은 커밋됐다. 메시지는 늦춰 다시 받는다(지연 재발행, D4).
     */
    record Deferred(OrderStatus status) implements CancelSettlement {
    }
}
