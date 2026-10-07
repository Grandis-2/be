package com.grandis.nova.catalog.registration;

import com.grandis.nova.catalog.product.SaleMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 관리자의 "한 번 등록" 요청. 상품 · 옵션 축과 값 · 조합 설정 · 사진 · 보증 · 판매 유형별 설정을 한 본문에 담는다.
 *
 * 옵션 조합은 서버가 축의 값으로 전부 만든다. {@link #combinations} 는 그중 일부를 손보는 자리다 — 제외 · SKU · 가격 수동 지정 · 초기 재고.
 * 조합의 selections 는 축 키 → 값(입력값. 저장과 같은 규칙으로 정규화해 대조한다).
 *
 * 형식 검사(필수 · 길이 · 범위)는 여기 애너테이션이, 조합 · 축 · 사진 묶음 · 판매 유형 규칙은 {@link ProductRegistrationValidator} 가 한다.
 */
public record ProductRegistrationRequest(
        @NotNull Long categoryId,
        @NotNull SaleMode saleMode,
        @NotBlank @Size(max = 100) String title,
        @Size(max = 5000) String description,
        @Size(max = 500) String tags,
        @NotNull Boolean visible,
        @NotNull @DecimalMin("0") BigDecimal basePrice,
        @Valid Warranty warranty,
        @Size(max = MAX_AXES) List<@NotNull @Valid OptionAxis> optionAxes,
        List<@NotNull @Valid Combination> combinations,
        @Valid Images images,
        @Valid Campaign campaign,
        List<@NotNull @Valid ShipmentBatch> shipmentBatches
) {

    /** 축 5개 · 축당 값 20개 · 조합 500개. 조합은 곱이라 상한이 없으면 수백만 행을 만들 수 있다. */
    public static final int MAX_AXES = 5;
    public static final int MAX_VALUES_PER_AXIS = 20;
    public static final int MAX_COMBINATIONS = 500;

    public ProductRegistrationRequest {
        optionAxes = frozen(optionAxes);
        combinations = frozen(combinations);
        shipmentBatches = frozen(shipmentBatches);
        warranty = warranty == null ? new Warranty(false, BigDecimal.ZERO) : warranty;
        images = images == null ? new Images(List.of(), List.of()) : images;
    }

    /** null 원소를 남긴 채 얼린다 — List.copyOf 는 null 원소에 NPE 를 던져 {@code @NotNull} 원소 검사(칸 경로)가 돌기 전에 죽는다. */
    static <T> List<T> frozen(List<T> list) {
        return list == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
    }

    @Schema(name = "RegistrationWarranty")
    public record Warranty(@NotNull Boolean offered, @DecimalMin("0") BigDecimal surcharge) {
        public Warranty {
            surcharge = surcharge == null ? BigDecimal.ZERO : surcharge;
        }
    }

    /** @param key color · storage 또는 관리자 입력 키(소문자로 접는다) */
    @Schema(name = "RegistrationOptionAxis")
    public record OptionAxis(@NotBlank @Size(max = 40) String key, @NotBlank @Size(max = 60) String label,
                             @NotEmpty @Size(max = MAX_VALUES_PER_AXIS) List<@NotNull @Valid OptionValue> values) {
        public OptionAxis {
            values = frozen(values);
        }
    }

    @Schema(name = "RegistrationOptionValue")
    public record OptionValue(@NotBlank @Size(max = 60) String value, @DecimalMin("0") BigDecimal surcharge) {
        public OptionValue {
            surcharge = surcharge == null ? BigDecimal.ZERO : surcharge;
        }
    }

    /**
     * 조합 하나의 설정. selections 가 빈 조합은 축이 없는 상품의 유일한 옵션이다.
     *
     * @param excluded 이 조합은 판매하지 않는다(행을 만들지 않는다)
     * @param sku      비면 값의 정규화값을 '-' 로 이어 만든다
     * @param stock    일반 상품의 초기 재고. 일반은 제외하지 않은 조합마다 필수(0 허용 — 품절로 공개), 사전예약은 보내지 않는다
     */
    @Schema(name = "RegistrationCombination")
    public record Combination(@NotNull Map<String, String> selections, Boolean excluded, @Size(max = 80) String sku,
                              Integer stock) {
        public Combination {
            excluded = excluded != null && excluded;
        }
    }

    /**
     * @param gallery color 축이 있으면 색상 묶음만(묶음마다 color 필수), 없으면 기본 묶음(color null) 하나. 묶음당 최대 10장, 첫 장이 대표(primary 로 바꿀 수 있다)
     * @param detail  상세 영역별 이미지. 장수 상한과 대표 자동 지정이 없다(설계 §2.3 — 상세 콘텐츠에는 같은 제한을 정하지 않았다)
     */
    @Schema(name = "RegistrationImages")
    public record Images(List<@NotNull @Valid GalleryBundle> gallery, List<@NotNull @Valid DetailBundle> detail) {
        public Images {
            gallery = frozen(gallery);
            detail = frozen(detail);
        }
    }

    @Schema(name = "RegistrationGalleryBundle")
    public record GalleryBundle(@Size(max = 60) String color, @NotEmpty List<@NotNull @Valid Image> items) {
        public GalleryBundle {
            items = frozen(items);
        }
    }

    @Schema(name = "RegistrationDetailBundle")
    public record DetailBundle(@NotBlank @Size(max = 60) String section, @NotEmpty List<@NotNull @Valid Image> items) {
        public DetailBundle {
            items = frozen(items);
        }
    }

    @Schema(name = "RegistrationImage")
    public record Image(@NotBlank @Size(max = 1000) String url, Boolean primary) {
        public Image {
            primary = primary != null && primary;
        }
    }

    @Schema(name = "RegistrationCampaign")
    public record Campaign(@NotNull Instant opensAt, @NotNull Instant closesAt) {
    }

    @Schema(name = "RegistrationShipmentBatch")
    public record ShipmentBatch(@NotNull Integer batchNumber, @NotNull Long positionFrom, Long positionTo,
                                @NotNull LocalDate estimatedShipStart, @NotNull LocalDate estimatedShipEnd) {
    }
}
