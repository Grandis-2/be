package com.grandis.nova.payment.deadletter.domain;

import java.time.Duration;
import java.time.Instant;

/** DLQ 에서 옮겨 온 메시지의 처리 상태. 전이는 모두 현재 상태를 조건으로 한 UPDATE 로만 한다. */
public enum DeadLetterStatus {

    /** 쌓임. 되돌리거나 버릴 수 있다. */
    OPEN,
    /** 원래 큐로 보내는 중(겹쳐 보내기를 막는 선점). */
    REDRIVING,
    /** 원래 큐로 보냈다. 처리 결과를 기다린다. */
    REDRIVEN,
    /**
     * 되돌린 메시지를 분배기가 예외 없이 처리했다. 환불이 열렸다는 뜻은 아니다 — 이미 진행 중 · 이미 환불 · 마지막 환불 확정 실패면
     * 환불 요청은 아무것도 열지 않고 끝난다(확정 실패는 RefundRequests 의 WARN 로그). 환불 여부는 그 주문의 결제 기록으로 본다.
     */
    SUCCEEDED,
    /** 되돌린 메시지가 또 DLQ 로 왔다. 새 행이 이 행을 가리킨다. */
    REDRIVE_FAILED,
    /** 버림(사유 필수). */
    DISCARDED;

    /** 보내다 죽거나 실패해 이만큼 넘게 REDRIVING 인 행은 다시 선점한다. 보내기 한 번(SQS 제한 시간)보다 충분히 길다. */
    public static final Duration STALE_REDRIVE = Duration.ofMinutes(1);

    /** 되돌리기를 기다리는가 — OPEN 이거나, 보내다 멈춰 staleBefore 전부터 REDRIVING 이다. */
    public boolean waitingForRedrive(Instant redriveStartedAt, Instant staleBefore) {
        return this == OPEN || (this == REDRIVING && redriveStartedAt.isBefore(staleBefore));
    }
}
