package com.grandis.nova.order.draw.api;

import com.grandis.nova.order.draw.AdminDrawService;
import com.grandis.nova.order.web.ValidationFailures;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 럭키 드로우 회차 만들기.
 *
 * @param productId   증정품 상품(옵션과 짝이 맞아야 한다)
 * @param optionId    증정품 옵션 — 일반 판매 · 판매 중 · 재고 등록. 비공개 상품이어도 된다
 * @param entryFee    응모비(원, 1 이상 · 정수)
 * @param winnerCount 당첨 인원 — 이만큼 재고를 확보한다
 */
public record CreateDrawRequest(
        @NotNull UUID productId,
        @NotNull UUID optionId,
        @NotBlank @Size(max = 100) String title,
        @NotNull @Positive @Digits(integer = 12, fraction = 0) BigDecimal entryFee,
        @NotNull @Min(1) Integer winnerCount,
        @NotNull Instant opensAt,
        @NotNull Instant closesAt
) {

    AdminDrawService.CreateDraw toCommand() {
        if (!opensAt.isBefore(closesAt)) {
            throw ValidationFailures.of("closesAt", "응모 마감은 시작보다 뒤여야 합니다.");
        }
        return new AdminDrawService.CreateDraw(productId, optionId, title.strip(), entryFee, winnerCount, opensAt, closesAt);
    }
}
