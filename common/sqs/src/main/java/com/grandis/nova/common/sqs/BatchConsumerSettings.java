package com.grandis.nova.common.sqs;

import java.time.Duration;

/**
 * {@link BatchQueueConsumer} 설정. 서비스가 자기 설정 키로 받아 이 값으로 넘긴다.
 *
 * @param waitSeconds 롱 폴링 대기. 받기 멈춤 · 종료는 진행 중인 롱 폴링이 끝난 뒤부터 듣는다
 * @param visibility  받은 메시지를 다른 소비자에게 숨기는 시간. 처리 한 건보다 길게 둔다
 * @param idleWait    빈 자리가 없거나 받기를 멈춘 동안 다시 확인하는 간격
 * @param stopTimeout 종료 때 처리 중인 메시지를 기다리는 시간. 넘으면 인터럽트한다
 */
public record BatchConsumerSettings(
        String queue,
        int waitSeconds,
        Duration visibility,
        Duration idleWait,
        Duration stopTimeout
) {

    private static final Duration MAX_VISIBILITY = Duration.ofHours(12);

    public BatchConsumerSettings {
        if (queue == null || queue.isBlank() || visibility == null || idleWait == null || stopTimeout == null
                || waitSeconds < 0 || waitSeconds > 20
                || visibility.compareTo(Duration.ofSeconds(1)) < 0 || visibility.compareTo(MAX_VISIBILITY) > 0
                || !idleWait.isPositive() || !stopTimeout.isPositive()) {
            throw new IllegalArgumentException("묶음 소비 설정: queue 필수, wait-seconds 0~20, visibility 1s~12h, "
                    + "idle-wait > 0, stop-timeout > 0 이어야 한다: " + queue);
        }
    }
}
