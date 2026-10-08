package com.grandis.nova.preorder.preorder;

import java.time.Instant;
import java.util.UUID;

/**
 * 관리자 예약 검색 조건. 값이 없으면 null 이고 그 조건은 걸지 않는다.
 *
 * @param registerJobStatus 외부 등록(REGISTER) 작업의 상태 이름(DEAD_LETTER 등)
 */
public record AdminPreorderSearch(
        PreorderStatus status,
        UUID customerId,
        UUID productId,
        Instant from,
        Instant to,
        String registerJobStatus
) {
}
