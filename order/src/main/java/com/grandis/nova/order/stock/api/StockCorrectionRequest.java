package com.grandis.nova.order.stock.api;

import com.grandis.nova.order.order.vo.EventCause;
import com.grandis.nova.order.web.ValidationFailures;
import jakarta.validation.constraints.NotBlank;

/**
 * 재고 보정 요청. 사유는 필수이고 보정 기록에 남는다. 규칙은 관리자 이력 사유({@link EventCause#admin})와 같다 — 공백은 {@code @NotBlank} 가,
 * 길이(500자)는 {@link #validReason} 이 코드 포인트로 본다({@code @Size} 는 UTF-16 단위라 이모지를 2자로 센다).
 */
public record StockCorrectionRequest(@NotBlank String reason) {

    String validReason() {
        if (reason.codePointCount(0, reason.length()) > EventCause.MAX_REASON_LENGTH) {
            throw ValidationFailures.of("reason", "%d자 이하여야 합니다.".formatted(EventCause.MAX_REASON_LENGTH));
        }
        try {
            return EventCause.admin(reason).reason();
        } catch (IllegalArgumentException e) {
            throw ValidationFailures.of("reason", "사용할 수 없는 사유입니다.");
        }
    }
}
