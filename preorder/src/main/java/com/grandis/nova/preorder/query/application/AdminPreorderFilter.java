package com.grandis.nova.preorder.query.application;

import com.grandis.nova.preorder.preorder.PreorderStatus;
import com.grandis.nova.preorder.syncjob.SyncJobStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * 관리자 목록 조건. 값이 없으면 null 이고 그 조건은 걸지 않는다.
 *
 * @param externalReference 외부가 준 예약 번호(정확히 같은 것)
 * @param displayStatus     화면 단계. status 와 함께 주고 서로 맞지 않으면 아무것도 나오지 않는다
 * @param optionId          productId 와 함께 준다
 * @param batchNumber       배송 차수 번호. productId 와 함께 준다
 */
public record AdminPreorderFilter(
        PreorderStatus status,
        UUID customerId,
        UUID productId,
        Instant from,
        Instant to,
        SyncJobStatus registerJobStatus,
        String externalReference,
        PreorderDisplayStatus displayStatus,
        UUID optionId,
        Integer batchNumber
) {
}
