package com.grandis.nova.worker.event;

import java.util.UUID;

/** 예약 작업 한 차례의 결과(성공 · 실패한 차례 · 포기). 어느 작업의 어느 차례인지로 이벤트 id 를 정한다. */
public interface JobResult {

    UUID syncJobId();

    Attempt attempt();
}
