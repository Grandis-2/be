package com.grandis.nova.preorder.integration.sqs;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

/**
 * @param endpoint       로컬 에뮬레이터(Floci) 주소. 비우면 AWS 기본 주소와 기본 자격 증명을 쓴다
 * @param apiCallTimeout SQS 호출 한 번의 제한 시간. 같은 키를 common:sqs 의 클라이언트 설정도 읽는다
 * @param deadLetter     소비 큐(consumer.queue)의 DLQ 를 DB 로 옮기는 소비기
 */
@ConfigurationProperties("nova.sqs")
record SqsProperties(
        String region,
        URI endpoint,
        @DefaultValue("test") String accessKey,
        @DefaultValue("test") String secretKey,
        @DefaultValue("3s") Duration apiCallTimeout,
        @DefaultValue Consumer consumer,
        @DefaultValue DeadLetter deadLetter
) {

    /**
     * @param visibility  받은 메시지를 다른 소비자에게 숨기는 시간. 처리 한 건(회차 취소 포함)보다 길게 둔다
     * @param backoffBase 처리 실패 후 다시 보이기까지의 첫 대기. 실패할수록 두 배, backoffMax 까지
     */
    public record Consumer(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("preorder-events") String queue,
            @DefaultValue("1") int concurrency,
            @DefaultValue("20") int waitSeconds,
            @DefaultValue("10") int maxMessages,
            @DefaultValue("5m") Duration visibility,
            @DefaultValue("5s") Duration backoffBase,
            @DefaultValue("5m") Duration backoffMax
    ) {

        private static final Duration MAX_VISIBILITY = Duration.ofHours(12);

        /** SQS 가 받는 범위 밖이거나 대기 설정이 어긋나면 받기 · 재시도가 뜻대로 되지 않으므로 기동할 때 알린다. */
        public Consumer {
            if (concurrency < 1 || maxMessages < 1 || maxMessages > 10 || waitSeconds < 0 || waitSeconds > 20
                    || visibility.compareTo(Duration.ofSeconds(1)) < 0 || visibility.compareTo(MAX_VISIBILITY) > 0
                    || !backoffBase.isPositive() || backoffBase.compareTo(backoffMax) > 0
                    || backoffMax.compareTo(MAX_VISIBILITY) > 0) {
                throw new IllegalArgumentException("nova.sqs.consumer: concurrency >= 1, max-messages 1~10, "
                        + "wait-seconds 0~20, visibility 1s~12h, 0 < backoff-base <= backoff-max <= 12h 여야 한다");
            }
        }
    }

    /**
     * @param queue      DLQ 이름. 소비 큐와 짝이다
     * @param visibility 받은 메시지를 숨기는 시간. 적재가 실패하면 이 시간 뒤 다시 받는다
     */
    public record DeadLetter(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("preorder-events-dlq") String queue,
            @DefaultValue("20") int waitSeconds,
            @DefaultValue("1m") Duration visibility
    ) {

        public DeadLetter {
            if (waitSeconds < 0 || waitSeconds > 20 || visibility.compareTo(Duration.ofSeconds(1)) < 0
                    || visibility.compareTo(Consumer.MAX_VISIBILITY) > 0) {
                throw new IllegalArgumentException("nova.sqs.dead-letter: wait-seconds 0~20, visibility 1s~12h 여야 한다");
            }
        }
    }
}
