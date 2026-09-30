package com.grandis.nova.preorder.deadletter;

/** DLQ 에서 옮겨 온 메시지의 처리 상태. 전이는 모두 현재 상태를 조건으로 한 UPDATE 로만 한다. */
enum DeadLetterStatus {

    /** 쌓임. 되돌리거나 버릴 수 있다. */
    OPEN,
    /** 원래 큐로 보내는 중(겹쳐 보내기를 막는 선점). */
    REDRIVING,
    /** 원래 큐로 보냈다. 처리 결과를 기다린다. */
    REDRIVEN,
    /** 되돌린 메시지가 처리됐다. */
    SUCCEEDED,
    /** 되돌린 메시지가 또 DLQ 로 왔다. 새 행이 이 행을 가리킨다. */
    REDRIVE_FAILED,
    /** 버림(사유 필수). */
    DISCARDED
}
