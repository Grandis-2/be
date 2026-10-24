package com.grandis.nova.payment.deadletter.application;

import com.grandis.nova.payment.deadletter.domain.DeadLetterEvent;

/** @param redrivable 지금 되돌릴 수 있는가 — 되돌리기를 기다리고(OPEN · 오래된 REDRIVING), 지금 코드로 읽을 수 있는 받는 종류 */
public record DeadLetterView(DeadLetterEvent event, boolean redrivable) {
}
