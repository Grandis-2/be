package com.grandis.nova.payment.deadletter;

import java.time.Instant;

/**
 * 되돌리기를 기다리는 DLQ 메시지.
 *
 * @param oldestAt 그중 가장 오래된 것이 쌓인 시각. 없으면 null
 */
public record DeadLetterWaiting(long count, Instant oldestAt) {
}
