package com.grandis.nova.preorder.syncjob;

/**
 * 작업 진행 상태. preorder 가 쓰는 값은 PENDING(생성)과 CANCELED(REGISTER 무효화) 둘뿐이다.
 * 나머지는 worker 가 쓴다. DEAD_LETTER · CANCELED 는 REGISTER 전용이다(DB CHECK).
 */
public enum SyncJobStatus {
    PENDING,
    PROCESSING,
    /** SQS 가 메시지를 숨겨 두고 있다. 다시 깨우는 시각은 SQS 가 들고 있다. */
    RETRY_SCHEDULED,
    SUCCEEDED,
    DEAD_LETTER,
    CANCELED
}
