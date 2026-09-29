package com.grandis.nova.catalog.option;

import com.grandis.nova.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.grandis.nova.catalog.product.Amounts;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;

/**
 * 축의 값 하나(블랙 · 256GB …)와 추가금.
 * value 는 표시값, normalizedValue 는 비교 · 필터 키다. 같은 축 안에서 normalizedValue 는 유일하다(DB UNIQUE, 대소문자 · 악센트 무시).
 * 콜레이션이 안 해 주는 것(트림 · 공백 접기 · NFC)은 여기서 한다 — 안 하면 '블랙 ' 이 '블랙' 옆에 들어간다(실측).
 * 축에 따라 다른 규칙은 {@link #normalizeFor} 한 곳에 둔다 — 저장(등록)과 목록 필터가 같은 메서드를 써야 둘이 어긋나지 않는다.
 * 용량은 공백을 없애고 단위를 대문자로("256 gb" → "256GB"), 그 밖의 축은 공통 규칙만.
 */
@Entity
@Table(name = "product_option_values")
public class ProductOptionValue extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long axisId;

    @Column(nullable = false, length = 60)
    private String value;

    @Column(nullable = false, updatable = false, length = 60)
    private String normalizedValue;

    @Column(nullable = false)
    private BigDecimal surcharge;

    @Column(nullable = false)
    private int position;

    protected ProductOptionValue() {
    }

    private ProductOptionValue(Long axisId, String value, String normalizedValue, BigDecimal surcharge, int position) {
        if (position < 0) {
            throw new IllegalArgumentException("position must be zero or positive: " + position);
        }
        this.axisId = axisId;
        this.value = normalize(value);
        this.normalizedValue = normalize(normalizedValue);
        this.surcharge = Amounts.requireWholeWon(surcharge, "surcharge");
        this.position = position;
    }

    public static ProductOptionValue of(Long axisId, String value, String normalizedValue, BigDecimal surcharge,
                                        int position) {
        return new ProductOptionValue(axisId, value, normalizedValue, surcharge, position);
    }

    /** 축 키에 맞는 정규화. 저장과 필터가 같이 쓴다. */
    public static String normalizeFor(String axisKey, String text) {
        String normalized = normalize(text);
        if (ProductOptionAxis.STORAGE.equals(axisKey)) {
            return normalized.replace(" ", "").toUpperCase(Locale.ROOT);
        }
        return normalized;
    }

    /** NFC · 트림 · 연속 공백을 하나로. 비면 거절한다. */
    public static String normalize(String text) {
        if (text == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        return normalized;
    }

    /**
     * 표시 문구 수정(오타 · 띄어쓰기). 정규화값은 바꿀 수 없다(칼럼 updatable=false) — 정규화값이 달라지는 변경은 구성 변경이라
     * 새 값 추가 + 기존 옵션 판매 중지로 한다(설계 §2.1 "옵션 변경"). 정규화가 같은지는 축 키를 아는 서비스가 확인한다.
     */
    public void rename(String value) {
        this.value = normalize(value);
    }

    /** 추가금 수정. 호출자가 이 값을 고른 옵션의 가격을 재계산한다(수동 가격 제외). */
    public void reprice(BigDecimal surcharge) {
        this.surcharge = Amounts.requireWholeWon(surcharge, "surcharge");
    }

    public Long getId() {
        return id;
    }

    public Long getAxisId() {
        return axisId;
    }

    public String getValue() {
        return value;
    }

    public String getNormalizedValue() {
        return normalizedValue;
    }

    public BigDecimal getSurcharge() {
        return surcharge;
    }

    public int getPosition() {
        return position;
    }
}
