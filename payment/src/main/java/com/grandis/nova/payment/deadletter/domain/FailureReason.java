package com.grandis.nova.payment.deadletter.domain;

import com.grandis.nova.payment.event.InboundEventType;

/** 적재 때 본문으로 가른 실패 분류. 실제 예외는 message_id 로 처리 실패 로그에서 찾는다. */
public enum FailureReason {

    /** 봉투로 읽을 수 없다(JSON 아님 · 모양이 다름 — 받는 종류인데 주문 aggregate 가 아닌 것도 여기다). */
    UNREADABLE_BODY,
    /** 받지 않는 이벤트 종류. */
    UNKNOWN_EVENT_TYPE,
    /** 읽을 수 있는 주문 이벤트인데 처리 중 실패했다(코드 · 스키마 · 데이터 문제 — 결제 기록이 아직 없는 환불 요청 등). */
    PROCESSING_FAILED;

    /**
     * 원문을 다시 읽지 않고 저장된 분류 · 종류로 본 되돌리기 가능 여부(목록 · 일괄 후보용). 지금 받는 종류여야 한다 — 모르던 종류는
     * 받게 됐으면 가능하고, 처리 실패였어도 그 뒤 받지 않게 된 종류면 되돌려도 거절된다. 못 읽던 원문을 배포로 읽게 된 경우는
     * 여기서 알 수 없다 — 상세 · 되돌리기가 원문을 다시 읽어 가른다.
     */
    public boolean redrivableFor(String eventType) {
        return this != UNREADABLE_BODY && InboundEventType.isKnown(eventType);
    }
}
