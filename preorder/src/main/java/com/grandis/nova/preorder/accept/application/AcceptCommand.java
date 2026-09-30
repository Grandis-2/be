package com.grandis.nova.preorder.accept.application;

import com.grandis.nova.preorder.preorder.EventActor;

/**
 * 검증을 마친 접수 요청.
 *
 * @param admissionTicketId 소비할 입장권 ID. 관리자 대신 접수면 null
 * @param reason            관리자 대신 접수의 사유(이력). 사용자 접수면 null
 * @param internalNote      관리자 메모. 없으면 null
 */
public record AcceptCommand(
        Long customerId,
        Long productId,
        Long optionId,
        String idempotencyKey,
        String admissionTicketId,
        EventActor actor,
        String reason,
        String internalNote
) {
}
