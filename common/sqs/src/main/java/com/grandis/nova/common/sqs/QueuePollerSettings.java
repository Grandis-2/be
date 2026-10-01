package com.grandis.nova.common.sqs;

import java.time.Duration;

/**
 * 큐 하나를 받는 소비기 설정. 서비스가 자기 설정 키(nova.sqs.consumer 등)로 받아 이 값으로 넘긴다.
 * SQS 가 받는 범위 밖이거나 대기 설정이 어긋나면 받기 · 재시도가 뜻대로 되지 않으므로 만들 때 알린다.
 *
 * @param visibility  받은 메시지를 다른 소비자에게 숨기는 시간. 처리 한 건보다 길게 둔다
 * @param backoffBase 처리 실패 후 다시 보이기까지의 첫 대기. 실패할수록 두 배, backoffMax 까지
 */
public record QueuePollerSettings(
        String queue,
        int concurrency,
        int waitSeconds,
        int maxMessages,
        Duration visibility,
        Duration backoffBase,
        Duration backoffMax
) {

    private static final Duration MAX_VISIBILITY = Duration.ofHours(12);

    public QueuePollerSettings {
        if (queue == null || queue.isBlank() || concurrency < 1 || maxMessages < 1 || maxMessages > 10
                || waitSeconds < 0 || waitSeconds > 20
                || visibility.compareTo(Duration.ofSeconds(1)) < 0 || visibility.compareTo(MAX_VISIBILITY) > 0
                || !backoffBase.isPositive() || backoffBase.compareTo(backoffMax) > 0
                || backoffMax.compareTo(MAX_VISIBILITY) > 0) {
            throw new IllegalArgumentException("큐 소비 설정: queue 필수, concurrency >= 1, max-messages 1~10, "
                    + "wait-seconds 0~20, visibility 1s~12h, 0 < backoff-base <= backoff-max <= 12h 여야 한다: " + queue);
        }
    }
}
