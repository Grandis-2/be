package com.grandis.nova.catalog.option;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/**
 * 조합(product_options 행)이 축마다 고른 값.
 *
 * "옵션과 축이 같은 상품" · "값이 그 축의 값" 은 DB 의 복합 FK 가, "축마다 값 하나" 는 PK 가 지킨다.
 * 시각 칸이 없어 BaseEntity 를 쓰지 않는다 — 옵션과 함께 만들고 옵션과 함께 산다.
 *
 * 키를 직접 할당하므로 {@link Persistable} 로 새 객체임을 알린다. 안 그러면 Spring Data 의 save() 가 merge 를 타서
 * 같은 (옵션, 축) 에 다른 값을 넣는 쓰기가 오류가 아니라 무시로 끝난다(실측 — value_id 가 updatable=false 라 UPDATE 할 칸이 없다).
 * 같은 이유로 새로 만든 인스턴스를 delete() 에 넘기면 아무것도 안 지운다(실측) — 삭제는 deleteById 나 조회한 인스턴스로 한다.
 * 만드는 곳은 {@link OptionCombination#selections} 하나다 — 옵션의 조합 키 · filter JSON 과 같은 재료에서 나와야 어긋나지 않는다.
 */
@Entity
@Table(name = "product_option_selections")
public class ProductOptionSelection implements Persistable<ProductOptionSelectionId> {

    @EmbeddedId
    private ProductOptionSelectionId id;

    @Transient
    private boolean isNew = true;

    @Column(nullable = false, updatable = false)
    private Long productId;

    @Column(nullable = false, updatable = false)
    private Long valueId;

    protected ProductOptionSelection() {
    }

    private ProductOptionSelection(Long productId, Long optionId, Long axisId, Long valueId) {
        this.id = new ProductOptionSelectionId(optionId, axisId);
        this.productId = productId;
        this.valueId = valueId;
    }

    /** 패키지 밖에서는 못 부른다 — 선택 행은 {@link OptionCombination#selections} 로만 만든다(컴파일러가 지침을 대신 지킨다). */
    static ProductOptionSelection of(Long productId, Long optionId, Long axisId, Long valueId) {
        return new ProductOptionSelection(productId, optionId, axisId, valueId);
    }

    @Override
    public ProductOptionSelectionId getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public Long getProductId() {
        return productId;
    }

    public Long getValueId() {
        return valueId;
    }
}
