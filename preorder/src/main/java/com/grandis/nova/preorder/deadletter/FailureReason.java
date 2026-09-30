package com.grandis.nova.preorder.deadletter;

/** 적재 때 본문으로 가른 실패 분류. 실제 예외는 message_id 로 처리 실패 로그에서 찾는다. */
enum FailureReason {

    /** 봉투로 읽을 수 없다(JSON 아님 · 모양이 다름). */
    UNREADABLE_BODY,
    /** 받지 않는 이벤트 종류. */
    UNKNOWN_EVENT_TYPE,
    /** 읽을 수 있는 이벤트인데 처리 중 실패했다(코드 · 스키마 · 데이터 문제). */
    PROCESSING_FAILED
}
