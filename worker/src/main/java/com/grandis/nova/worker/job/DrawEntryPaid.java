package com.grandis.nova.worker.job;

import java.util.UUID;

/** DRAW_ENTRY_PAID 의 payload. 응모 id 는 봉투의 aggregateId 다. worker 는 order 를 읽지 못하므로 Mock 에 보낼 값이 실려 온다. */
public record DrawEntryPaid(UUID drawId, UUID customerId) {
}
