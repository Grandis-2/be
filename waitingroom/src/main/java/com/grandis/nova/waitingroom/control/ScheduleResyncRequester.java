package com.grandis.nova.waitingroom.control;

import reactor.core.publisher.Mono;

import java.time.Duration;

/** preorder 에 회차 일정 전체 재발행을 요청한다. SQS 가 없는 구성(로컬)에서는 로그만 남기는 구현이 대신한다. */
public interface ScheduleResyncRequester {

    Mono<Void> request(String reason);

    /** 요청 한 번이 걸릴 수 있는 최대 시간. 재발행 선점 시한이 이보다 길어야 한 요청이 끝나기 전에 선점이 풀리지 않는다. */
    default Duration maxRequestTime() {
        return Duration.ZERO;
    }
}
