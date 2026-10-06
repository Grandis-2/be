package com.grandis.nova.catalog.option;

import com.grandis.nova.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Locale;

/**
 * 옵션 축 — 상품이 받는 옵션의 종류(색상 · 용량 · 관리자가 정한 것).
 * axisKey 가 color · storage 면 목록 필터 키다. 그 밖은 상세에서만 고른다.
 * 칼럼이 대소문자를 구분(utf8mb4_bin)하므로 키는 트림해 소문자로 접어 저장한다 — Color 와 color 가 공존하지 않게.
 */
@Entity
@Table(name = "product_option_axes")
public class ProductOptionAxis extends BaseEntity {

    public static final String COLOR = "color";
    public static final String STORAGE = "storage";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long productId;

    @Column(nullable = false, updatable = false, length = 40)
    private String axisKey;

    @Column(nullable = false, length = 60)
    private String label;

    @Column(nullable = false)
    private int position;

    protected ProductOptionAxis() {
    }

    private ProductOptionAxis(Long productId, String axisKey, String label, int position) {
        if (position < 0) {
            throw new IllegalArgumentException("position must be zero or positive: " + position);
        }
        if (axisKey == null || axisKey.isBlank()) {
            throw new IllegalArgumentException("axisKey must not be blank");
        }
        this.productId = productId;
        this.axisKey = axisKey.strip().toLowerCase(Locale.ROOT);
        this.label = label;
        this.position = position;
    }

    public static ProductOptionAxis of(Long productId, String axisKey, String label, int position) {
        return new ProductOptionAxis(productId, axisKey, label, position);
    }

    /** 목록 필터에 쓰는 축인가(color · storage). */
    public boolean isFilterAxis() {
        return COLOR.equals(axisKey) || STORAGE.equals(axisKey);
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public String getAxisKey() {
        return axisKey;
    }

    public String getLabel() {
        return label;
    }

    public int getPosition() {
        return position;
    }
}
