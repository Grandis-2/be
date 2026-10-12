package com.grandis.nova.preorder.syncjob;

/**
 * 작업 진행 상태. worker 가 보낸 결과 이벤트를 받아 preorder 가 바꾼다. PROCESSING 은 쓰지 않는다.
 * DEAD_LETTER · CANCELED 는 REGISTER 전용이다(DB CHECK).
 */
public enum SyncJobStatus {
    PENDING,
    PROCESSING,
    /** 실패한 차례가 있어 worker 가 메시지를 늦춰 다시 보냈다. 다시 깨우는 시각은 SQS 가 들고 있다. */
    RETRY_SCHEDULED,
    SUCCEEDED,
    DEAD_LETTER,
    CANCELED
}
