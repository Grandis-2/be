package com.grandis.nova.common.outbox;

/**
 * 발행 지표. 지표 레지스트리가 있는 서비스에서만 기록하고, 없으면 아무것도 하지 않는다.
 * 이 인터페이스는 micrometer 를 모른다 — 레지스트리가 없는 서비스에서 micrometer 클래스를 읽지 않게 한다.
 */
interface OutboxMetrics {

    OutboxMetrics NONE = new OutboxMetrics() {
    };

    /** 전송 결과를 이벤트 종류별로 센다. 실패가 늘면 전송 구현 · 큐 장애다. */
    default void published(String eventType, boolean success) {
    }

    /** 미발행 현황을 세는가. 세면 릴레이 실행기가 주기적으로 {@link #refreshBacklog} 를 부른다. */
    default boolean tracksBacklog() {
        return false;
    }

    default void refreshBacklog() {
    }

    /** 보존 기간이 지나 지운 발행 완료 행 수. */
    default void cleaned(int count) {
    }

    /** 정리가 실패했다. 계속 늘면 표가 줄지 않는다(DB 장애 · 권한). */
    default void cleanupFailed() {
    }
}
