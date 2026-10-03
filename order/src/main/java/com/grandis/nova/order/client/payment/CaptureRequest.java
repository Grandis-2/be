package com.grandis.nova.order.client.payment;

import java.math.BigDecimal;

/**
 * payment OpenCaptureRequest 와 같은 모양. 대상 종류는 payment 소유라 enum 으로 옮기지 않는다.
 *
 * @param amount 주문의 저장된 총액(orders.total_amount). 사용자가 보낸 값이 아니다
 */
public record CaptureRequest(String targetType, Long targetId, BigDecimal amount) {

    static final String ORDER = "ORDER";

    public static CaptureRequest order(Long orderId, BigDecimal amount) {
        return new CaptureRequest(ORDER, orderId, amount);
    }
}
