package com.grandis.nova.common.sqs;

import java.time.Duration;

/**
 * {@link BatchQueueConsumer} 설정. 서비스가 자기 설정 키로 받아 이 값으로 넘긴다.
 *
 * @param waitSeconds 롱 폴링 대기. 받기 멈춤 · 종료는 진행 중인 롱 폴링이 끝난 뒤부터 듣는다
 * @param visibility  받은 메시지를 다른 소비자에게 숨기는 시간. 처리 한 건보다 길게 둔다
 * @param stopTimeout 종료 때 처리 중인 메시지를 기다리는 시간. 넘으면 인터럽트한다
 */
public record BatchConsumerSettings(
        String queue,
        int waitSeconds,
        Duration visibility,
        Duration stopTimeout
) {

    private static final Duration MAX_VISIBILITY = Duration.ofHours(12);

    public BatchConsumerSettings {
        if (queue == null || queue.isBlank() || visibility == null || stopTimeout == null
                || waitSeconds < 0 || waitSeconds > 20
                || visibility.compareTo(Duration.ofSeconds(1)) < 0 || visibility.compareTo(MAX_VISIBILITY) > 0
                || !stopTimeout.isPositive()) {
            throw new IllegalArgumentException("묶음 소비 설정: queue 필수, wait-seconds 0~20, visibility 1s~12h, "
                    + "stop-timeout > 0 이어야 한다: " + queue);
        }
    }

    /** 받기 스레드는 하나(빈 자리 수를 한 곳에서 묻는다), 받을 개수는 그때그때 정한다. 재시도 대기는 쓰지 않아 자리만 채운다. */
    QueuePollerSettings pollerSettings() {
        return new QueuePollerSettings(queue, 1, waitSeconds, 10, visibility, visibility, visibility);
    }
}
