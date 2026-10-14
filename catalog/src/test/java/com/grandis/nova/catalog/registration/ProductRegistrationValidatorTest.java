package com.grandis.nova.catalog.registration;

import com.grandis.nova.catalog.image.ImageUploadProperties;
import com.grandis.nova.catalog.product.Brand;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
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

    static final String CDN = "https://cdn.example.com";

    final ProductRegistrationValidator validator =
            new ProductRegistrationValidator(new ImageUploadProperties("bucket", URI.create(CDN), null, null, "test", "test"));

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

    @Test
    @DisplayName("사진 주소 — CDN 주소 아래만 받고, 바깥 주소 · 비슷한 호스트 · 접두만 · 상세 영역도 그 칸 경로로 400")
    void imageUrlsMustBeUnderTheCdn() {
        assertThatCode(() -> validator.validate(withImages(CDN + "/products/a.png", CDN + "/products/b.webp"), NOW, LEAD))
                .as("대조군 — CDN 주소 아래").doesNotThrowAnyException();
        assertRejected(withImages("https://elsewhere.example/a.png", CDN + "/products/b.png"), "images.gallery[0].items[0].url");
        assertRejected(withImages(CDN + ".evil.test/a.png", CDN + "/products/b.png"), "images.gallery[0].items[0].url");
        assertRejected(withImages(CDN + "/", CDN + "/products/b.png"), "images.gallery[0].items[0].url");
        assertRejected(withImages("https://elsewhere.example/?u=" + CDN + "/products/a.png", CDN + "/products/b.png"),
                "images.gallery[0].items[0].url");
        assertRejected(withImages(CDN + "/products/a.png", "http://cdn.example.com/products/b.png"), "images.detail[0].items[0].url");
    }

    private void assertRejected(ProductRegistrationRequest request, String field) {
        assertThatThrownBy(() -> validator.validate(request, NOW, LEAD))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).details().toString()).contains("field=" + field));
    }

    /** 일반 판매 · 축 없음 — 기본 사진 묶음 하나와 상세 영역 하나에 한 장씩. */
    private static ProductRegistrationRequest withImages(String galleryUrl, String detailUrl) {
        var images = new ProductRegistrationRequest.Images(
                List.of(new ProductRegistrationRequest.GalleryBundle(null, List.of(new ProductRegistrationRequest.Image(galleryUrl, null)))),
                List.of(new ProductRegistrationRequest.DetailBundle("사양", List.of(new ProductRegistrationRequest.Image(detailUrl, null)))));
        return new ProductRegistrationRequest(UUID.randomUUID(), SaleMode.IN_STOCK, "Nova", Brand.APPLE, "A0001", null, null, true, new BigDecimal("1000"), null, null,
                null, List.of(new ProductRegistrationRequest.Combination(Map.of(), false, null, 1)), images, null, null);
    }

    private static ProductRegistrationRequest preorder(Instant opensAt) {
        return new ProductRegistrationRequest(UUID.randomUUID(), SaleMode.PREORDER, "Nova", Brand.APPLE, "A0001", null, null, true, new BigDecimal("1000"), null, null,
                null, List.of(new ProductRegistrationRequest.Combination(Map.of(), false, null, null)), null,
                new ProductRegistrationRequest.Campaign(opensAt, opensAt.plus(Duration.ofDays(1))),
                List.of(new ProductRegistrationRequest.ShipmentBatch(1, 1L, null, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7))));
    }
}
