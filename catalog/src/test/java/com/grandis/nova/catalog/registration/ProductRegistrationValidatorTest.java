package com.grandis.nova.catalog.registration;

import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductRegistrationValidatorTest {

    static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    static final Duration LEAD = Duration.ofMinutes(30);

    final ProductRegistrationValidator validator = new ProductRegistrationValidator();

    @Test
    @DisplayName("오픈 여유 경계 — 지금 + 30분 정각은 통과, 1µs 전은 거절")
    void openLeadBoundaryIsInclusive() {
        assertThatCode(() -> validator.validate(preorder(NOW.plus(LEAD)), NOW, LEAD)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(preorder(NOW.plus(LEAD).minusNanos(1_000)), NOW, LEAD))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).details().toString()).contains("campaign.opensAt"));
    }

    @Test
    @DisplayName("콜레이션 비교 키 — 대소문자 · 악센트 · 전각을 같게, NFD 도 같게")
    void collationKeyFoldsWhatTheDatabaseFolds() {
        assertThat(ProductRegistrationValidator.collationKey("Rosé")).isEqualTo(ProductRegistrationValidator.collationKey("rose"));
        assertThat(ProductRegistrationValidator.collationKey("ＢＬＡＣＫ")).isEqualTo(ProductRegistrationValidator.collationKey("black"));
        assertThat(ProductRegistrationValidator.collationKey("블랙")).isEqualTo(ProductRegistrationValidator.collationKey("블랙"));
        assertThat(ProductRegistrationValidator.collationKey("블랙")).isNotEqualTo(ProductRegistrationValidator.collationKey("화이트"));
    }

    private static ProductRegistrationRequest preorder(Instant opensAt) {
        return new ProductRegistrationRequest(UUID.randomUUID(), SaleMode.PREORDER, "Nova", null, null, true, new BigDecimal("1000"), null,
                null, List.of(new ProductRegistrationRequest.Combination(Map.of(), false, null, null)), null,
                new ProductRegistrationRequest.Campaign(opensAt, opensAt.plus(Duration.ofDays(1))),
                List.of(new ProductRegistrationRequest.ShipmentBatch(1, 1L, null, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7))));
    }
}
