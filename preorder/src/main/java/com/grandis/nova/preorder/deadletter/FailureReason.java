package com.grandis.nova.preorder.deadletter;

import com.grandis.nova.preorder.event.InboundEventType;

/** 적재 때 본문으로 가른 실패 분류. 실제 예외는 message_id 로 처리 실패 로그에서 찾는다. */
enum FailureReason {

    /** 봉투로 읽을 수 없다(JSON 아님 · 모양이 다름). */
    UNREADABLE_BODY,
    /** 받지 않는 이벤트 종류. */
    UNKNOWN_EVENT_TYPE,
    /** 읽을 수 있는 이벤트인데 처리 중 실패했다(코드 · 스키마 · 데이터 문제). */
    PROCESSING_FAILED;

    /**
     * 원문을 다시 읽지 않고 저장된 분류 · 종류로 본 되돌리기 가능 여부(목록 · 일괄 후보용). 모르는 종류는 지금 받게 됐으면 가능하다.
     * 못 읽던 원문을 배포로 읽게 된 경우는 여기서 알 수 없다 — 상세 · 되돌리기가 원문을 다시 읽어 가른다.
     */
    boolean redrivableFor(String eventType) {
        return this == PROCESSING_FAILED || (this == UNKNOWN_EVENT_TYPE && InboundEventType.isKnown(eventType));
    }
}
