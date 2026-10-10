package com.grandis.nova.order.draw.api;

import com.grandis.nova.order.draw.AdminDrawService;
import com.grandis.nova.order.web.ValidationFailures;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 럭키 드로우 회차 만들기.
 *
 * @param productId   증정품 상품(옵션과 짝이 맞아야 한다)
 * @param optionId    증정품 옵션 — 일반 판매 · 판매 중 · 재고 등록. 비공개 상품이어도 된다
 * @param entryFee    응모비(원, 100 이상 · 정수 — 토스가 너무 작은 금액을 거절할 수 있다)
 * @param opensAt     응모 시작 · 마감은 µs 로 자른다(DB 는 datetime(6) — 자르지 않으면 응답은 ns 그대로, 저장값은 µs 라 둘이 달라진다)
 * @param winnerCount 당첨 인원 — 이만큼 재고를 확보한다
 */
public record CreateDrawRequest(
        @NotNull UUID productId,
        @NotNull UUID optionId,
        @NotBlank @Size(max = 100) String title,
        @NotNull @DecimalMin("100") @Digits(integer = 12, fraction = 0) BigDecimal entryFee,
        @NotNull @Min(1) Integer winnerCount,
        @NotNull Instant opensAt,
        @NotNull Instant closesAt
) {

    /** DB datetime(6) 이 담는 가장 늦은 시각. 넘으면 저장할 수 없다. */
    static final Instant DB_INSTANT_MAX = Instant.parse("9999-12-31T23:59:59.999999Z");

    AdminDrawService.CreateDraw toCommand() {
        Instant opens = requireStorable("opensAt", opensAt);
        Instant closes = requireStorable("closesAt", closesAt);
        if (!opens.isBefore(closes)) {
            throw ValidationFailures.of("closesAt", "응모 마감은 시작보다 뒤여야 합니다.");
        }
        return new AdminDrawService.CreateDraw(productId, optionId, title.strip(), entryFee, winnerCount, opens, closes);
    }

    private static Instant requireStorable(String field, Instant value) {
        Instant micros = value.truncatedTo(ChronoUnit.MICROS);
        if (micros.isAfter(DB_INSTANT_MAX) || micros.isBefore(Instant.EPOCH)) {
            throw ValidationFailures.of(field, "저장할 수 없는 시각입니다.");
        }
        return micros;
    }
}
