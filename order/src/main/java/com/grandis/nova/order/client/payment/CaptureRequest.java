package com.grandis.nova.order.client.payment;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * payment OpenCaptureRequest 와 같은 모양. 대상 종류는 payment 소유라 enum 으로 옮기지 않는다.
 *
 * @param amount 대상의 저장 금액({@link PayableTarget}). 사용자가 보낸 값이 아니다
 */
public record CaptureRequest(String targetType, UUID targetId, BigDecimal amount) {

    public static CaptureRequest of(PayableTarget target) {
        return new CaptureRequest(target.type(), target.id(), target.amount());
    }
}
