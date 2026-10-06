package com.grandis.nova.payment.vo;

import com.grandis.nova.payment.domain.enums.TargetType;

import java.util.Objects;

/**
 * 무엇에 대한 결제인가. 대상 서비스의 내부 id 를 그대로 싣는다 — 결제는 그 id 로 대상 테이블을 찾지 않는다(외래 키 없음).
 * 대상당 성공한 결제는 하나다(uq_payment_target).
 */
public record PaymentTarget(TargetType type, Long id) {

    public PaymentTarget {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        if (id <= 0) {
            throw new IllegalArgumentException("대상 id 는 양수다: " + id);
        }
    }

    public static PaymentTarget order(Long orderId) {
        return new PaymentTarget(TargetType.ORDER, orderId);
    }

    public static PaymentTarget drawEntry(Long entryId) {
        return new PaymentTarget(TargetType.DRAW_ENTRY, entryId);
    }
}
