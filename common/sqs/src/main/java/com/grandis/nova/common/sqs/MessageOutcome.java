package com.grandis.nova.common.sqs;

import java.time.Duration;

/** {@link BatchQueueConsumer} 가 받은 메시지 하나를 어떻게 끝낼지. 처리 함수가 정해 돌려준다. */
public sealed interface MessageOutcome {

    /** 끝났다 — 지운다(다른 메시지와 묶어서). */
    static MessageOutcome delete() {
        return Delete.INSTANCE;
    }

    /**
     * 지우지 않고 delay 뒤 다시 보이게 한다(0 이면 곧바로). 받은 횟수가 쌓이므로 큐의 재드라이브 한도 안에서만 쓴다.
     *
     * @throws IllegalArgumentException delay 가 0 ~ 12시간 밖
     */
    static MessageOutcome returnAfter(Duration delay) {
        return new Return(delay);
    }

    /** 손대지 않는다 — 받을 때의 가시성 시간이 지나면 다시 보인다. */
    static MessageOutcome keep() {
        return Keep.INSTANCE;
    }

    enum Delete implements MessageOutcome {
        INSTANCE
    }

    enum Keep implements MessageOutcome {
        INSTANCE
    }

    record Return(Duration delay) implements MessageOutcome {

        private static final Duration MAX_VISIBILITY = Duration.ofHours(12);

        public Return {
            if (delay == null || delay.isNegative() || delay.compareTo(MAX_VISIBILITY) > 0) {
                throw new IllegalArgumentException("다시 보일 때까지의 시간은 0 ~ 12h 여야 한다: " + delay);
            }
        }

        /** SQS 는 초 단위다. 1초에 못 미치는 나머지는 올린다 — 0 보다 큰 지연이 곧바로 다시 보이지 않게. */
        int seconds() {
            return (int) delay.getSeconds() + (delay.getNano() > 0 ? 1 : 0);
        }
    }
}
