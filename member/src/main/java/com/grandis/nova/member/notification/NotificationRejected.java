package com.grandis.nova.member.notification;

/**
 * 계약(contracts/notification-events.md)에 맞지 않는 알림 요청. 다시 받아도 결과가 같아 재시도하지 않는다 —
 * {@link NotificationQueueHandler} 가 이유를 붙여 DLQ 에 바로 넣는다. DB 순단 같은 일시 오류는 이 예외로 바꾸지 않는다.
 */
class NotificationRejected extends RuntimeException {

    /** DLQ 메시지의 deadLetterReason 속성 값. 사람이 DLQ 를 볼 때 어느 쪽을 고칠지 가른다. */
    enum Reason {
        /** JSON 으로 풀 수 없다 */
        UNREADABLE,
        /** 알림 요청이 아닌 이벤트가 이 큐로 왔다 — 보내는 쪽 목적지 설정 오류 */
        NOT_NOTIFICATION,
        /** 봉투 칸(eventId · occurredAt · aggregateType)이 계약에 맞지 않는다 */
        INVALID_ENVELOPE,
        /** payload 칸이 비었거나 길이를 넘는다 */
        INVALID_PAYLOAD
    }

    private final Reason reason;

    NotificationRejected(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    NotificationRejected(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
