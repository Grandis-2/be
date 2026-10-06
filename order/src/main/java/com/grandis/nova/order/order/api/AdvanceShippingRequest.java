package com.grandis.nova.order.order.api;

import com.grandis.nova.order.order.ship.ShippingStep;
import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.web.ValidationFailures;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 관리자 배송 단계 요청. 사유는 필수이고 이력에 남는다(사용자 이력에는 나가지 않는다).
 *
 * 공백은 {@code @NotBlank} 가, 길이는 {@link #toCause} 가 코드 포인트로 본다 — {@code @Size} 는 UTF-16 단위라 이모지를 2자로 세어
 * DB(utf8mb4)와 어긋난다. 그 밖에 {@link EventCause} 가 거절하는 사유는 길이와 섞지 않고 따로 알린다.
 *
 * @param step   배송 단계. 배송이 아닌 사건(취소 · 환불 완료)은 이 enum 에 없어 400 이다
 * @param reason 관리자 사유
 */
public record AdvanceShippingRequest(
        @NotNull ShippingStep step,
        @NotBlank String reason
) {

    EventCause toCause() {
        if (reason.codePointCount(0, reason.length()) > EventCause.MAX_REASON_LENGTH) {
            throw ValidationFailures.of("reason", "%d자 이하여야 합니다.".formatted(EventCause.MAX_REASON_LENGTH));
        }
        try {
            return EventCause.admin(reason);
        } catch (IllegalArgumentException e) {
            throw ValidationFailures.of("reason", "사용할 수 없는 사유입니다.");
        }
    }
}
