package com.grandis.nova.catalog.event;

import com.grandis.nova.common.message.EventEnvelope;
import java.util.UUID;

/**
 * catalog-events 로 받는 이벤트 한 종류의 처리. 빈으로 두면 {@link CatalogEventDispatcher} 가 종류 이름으로 찾아 부른다.
 * 같은 메시지를 두 번 받아도 결과가 같아야 한다 — 표준 큐라 중복 · 순서 뒤집힘이 있다. 실패는 예외로 올린다(메시지를 지우지 않고 다시 받는다).
 */
public interface CatalogEventHandler {

    /** 봉투의 eventType. 처리기마다 달라야 한다(겹치면 기동 실패). */
    String eventType();

    /** 봉투의 aggregateType. 다르면 처리기를 부르지 않고 실패로 올린다 — 다른 aggregate 의 id 로 엉뚱한 대상을 바꾸지 않게. */
    String aggregateType();

    /** @param aggregateId 봉투의 aggregateId(null 이 아님). payload 도 null 이 아니다(JSON null 일 수는 있다) — 모양 검사는 처리기가 한다 */
    void handle(UUID aggregateId, EventEnvelope envelope);
}
