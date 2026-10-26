package com.grandis.nova.member.adminalert;

/**
 * 계약(contracts/admin-alert-events.md · admin-alert-grafana.md)에 맞지 않는 메시지. 다시 받아도 같아 재시도하지 않는다 —
 * {@link AdminAlertQueueHandler} 가 이유를 붙여 DLQ 에 바로 넣는다. DB 순단 같은 일시 오류는 이 예외로 바꾸지 않는다.
 */
class AdminAlertRejected extends RuntimeException {

    /** DLQ 메시지의 deadLetterReason 속성 값. 앞의 넷은 admin-alert 큐, 뒤의 둘은 Grafana 큐의 것이다. */
    enum Reason {
        /** JSON 으로 풀 수 없다 */
        UNREADABLE,
        /** 관리자 알림 요청이 아닌 이벤트가 admin-alert 큐로 왔다 — 보내는 쪽 목적지 설정 오류 */
        NOT_ADMIN_ALERT,
        /** 봉투 칸(eventId · occurredAt · aggregateType)이 계약에 맞지 않는다 */
        INVALID_ENVELOPE,
        /** payload 칸(type · target · metrics)이 계약에 맞지 않는다 */
        INVALID_PAYLOAD,
        /** Grafana 메시지의 version · alerts 가 계약에 맞지 않는다 */
        INVALID_MESSAGE,
        /** Grafana 경보 하나의 status · 시각 · 라벨이 계약에 맞지 않는다 — 규칙 설정 오류 */
        INVALID_ALERT
    }

    private final Reason reason;

    AdminAlertRejected(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    AdminAlertRejected(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
