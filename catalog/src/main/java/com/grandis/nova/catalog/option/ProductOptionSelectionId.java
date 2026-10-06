package com.grandis.nova.catalog.option;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/** 옵션 하나가 축 하나에 고른 값은 하나 — (optionId, axisId) 가 곧 키다. */
@Embeddable
public class ProductOptionSelectionId implements Serializable {

    @Column(nullable = false)
    private Long optionId;

    @Column(nullable = false)
    private Long axisId;

    protected ProductOptionSelectionId() {
    }

    public ProductOptionSelectionId(Long optionId, Long axisId) {
        this.optionId = optionId;
        this.axisId = axisId;
    }

    public Long getOptionId() {
        return optionId;
    }

    public Long getAxisId() {
        return axisId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProductOptionSelectionId that
                && Objects.equals(optionId, that.optionId) && Objects.equals(axisId, that.axisId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(optionId, axisId);
    }
}
