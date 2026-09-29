package com.grandis.nova.order.order.vo;

import com.grandis.nova.order.order.domain.enums.EventActor;

import java.util.Objects;

/**
 * 이력에 남길 "누가 · 왜". 관리자는 사유가 필요하고 사유는 500자 이하다(order_events.reason).
 * order_events 에는 preorder_events 같은 사유 CHECK 가 없어 이 값이 유일한 검사다.
 *
 * 원장을 부르기 전에 만든다. 규칙 위반을 원장 안에서 던지면 원장의 트랜잭션 프록시가 호출자의 트랜잭션을
 * rollback-only 로 만든다 — 입력 오류 하나로 같은 트랜잭션의 다른 작업까지 커밋할 수 없게 된다.
 *
 * @param reason 없으면 null. 공백뿐이면 없는 것으로 맞춘다
 */
public record EventCause(EventActor actor, String reason) {

    static final int MAX_REASON_LENGTH = 500;

    public EventCause {
        Objects.requireNonNull(actor, "actor");
        reason = reason == null || reason.isBlank() ? null : reason;
        if (actor == EventActor.ADMIN && reason == null) {
            throw new IllegalArgumentException("관리자 전이는 사유가 필요하다");
        }
        if (reason != null && reason.codePointCount(0, reason.length()) > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("사유는 %d자 이하여야 한다".formatted(MAX_REASON_LENGTH));
        }
    }

    public static EventCause user() {
        return new EventCause(EventActor.USER, null);
    }

    public static EventCause system(String reason) {
        return new EventCause(EventActor.SYSTEM, reason);
    }

    public static EventCause admin(String reason) {
        return new EventCause(EventActor.ADMIN, reason);
    }
}
