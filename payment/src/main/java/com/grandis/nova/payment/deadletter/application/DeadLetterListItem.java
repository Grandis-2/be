package com.grandis.nova.payment.deadletter.application;

import com.grandis.nova.payment.deadletter.domain.DeadLetterSummary;

/** @param redrivable 되돌리기를 기다리고, 저장된 분류 · 종류로 보아 되돌릴 수 있다(원문은 다시 읽지 않는다) */
public record DeadLetterListItem(DeadLetterSummary summary, boolean redrivable) {
}
