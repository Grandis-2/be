package com.grandis.nova.waitingroom.control;

import reactor.core.publisher.Mono;

/** preorder 에 회차 일정 전체 재발행을 요청한다. SQS 가 없는 구성(로컬)에서는 로그만 남기는 구현이 대신한다. */
public interface ScheduleResyncRequester {

    Mono<Void> request(String reason);
}
