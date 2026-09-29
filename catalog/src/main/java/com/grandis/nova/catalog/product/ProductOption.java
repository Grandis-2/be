package com.grandis.nova.catalog.product;

import com.grandis.nova.catalog.option.OptionCombination;
import com.grandis.nova.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 옵션 = 축마다 값 하나를 고른 조합(예: 블랙 / 256GB). 어느 값을 골랐는지는 product_option_selections 가 갖는다.
 *
 * price 는 최종가다. priceOverridden 이 true 면 관리자가 직접 고친 값이라 기본가 · 추가금 재계산에서 건너뛴다.
 * filterAttributes(color · storage 의 JSON) · title · combinationKey 와 선택 행은 한 {@link OptionCombination} 에서 함께 나온다 —
 * 따로 만들면 어긋날 자리가 열리고 DB 는 못 막는다. preorder 가 접수 때 filterAttributes 를 복사한다.
 * combinationKey 는 고른 값 id 를 오름차순으로 '-' 로 이은 것이라 키를 채운 옵션끼리는 같은 조합을 DB UNIQUE 가 막는다.
 * 축이 없는 상품의 옵션({@link OptionCombination#none})은 키가 {@link OptionCombination#STANDALONE_KEY}('')라 같은 UNIQUE 가
 * 상품당 하나를 지킨다 — NULL 은 catalog 밖(다른 모듈 픽스처)에서 넣은 행에만 있고 NULL 끼리는 UNIQUE 가 안 걸린다.
 * 재고는 여기 없다(order 소유 option_inventories). 사전예약 옵션에는 재고 행이 없다.
 */
@Entity
@Table(name = "product_options")
public class ProductOption extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long productId;

    @Column(nullable = false, updatable = false, length = 80)
    private String sku;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private boolean priceOverridden;

    @JdbcTypeCode(SqlTypes.JSON)
    private String filterAttributes;

    @JdbcTypeCode(SqlTypes.JSON)
    private String displayAttributes;

    @Column(length = 200)
    private String combinationKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SaleStatus status;

    protected ProductOption() {
    }

    private ProductOption(Long productId, String sku, String title, BigDecimal price, boolean priceOverridden,
                          String filterAttributes, String displayAttributes, String combinationKey) {
        this.productId = productId;
        this.sku = sku;
        this.title = title;
        this.price = Amounts.requireWholeWon(price, "price");
        this.priceOverridden = priceOverridden;
        this.filterAttributes = filterAttributes;
        this.displayAttributes = displayAttributes;
        this.combinationKey = combinationKey;
        this.status = SaleStatus.ACTIVE;
    }

    /**
     * 옵션 하나. 표시명 · 조합 키 · JSON 두 칸을 전부 조합에서 받는다(축이 없는 상품은 {@link OptionCombination#none}).
     * 판매 상태는 ACTIVE 로 시작한다. 선택 행은 저장 뒤 {@link OptionCombination#selections} 로 같은 트랜잭션에서 만든다.
     */
    public static ProductOption of(String sku, BigDecimal price, boolean priceOverridden, OptionCombination combination) {
        return new ProductOption(combination.getProductId(), sku, combination.title(), price, priceOverridden,
                combination.filterAttributes(), combination.displayAttributes(), combination.combinationKey());
    }

    /** 관리자가 직접 고친 가격. 이후 기본 가격 · 추가금이 바뀌어도 재계산에서 빠진다(설계 §2.1). */
    public void overridePrice(BigDecimal price) {
        this.price = Amounts.requireWholeWon(price, "price");
        this.priceOverridden = true;
    }

    /** 재계산 결과. 수동 가격이면 무시한다 — 호출자가 가르지 않아도 여기서 지킨다. */
    public void recomputePrice(BigDecimal computed) {
        if (!priceOverridden) {
            this.price = Amounts.requireWholeWon(computed, "price");
        }
    }

    /** 판매 중지 · 재개. 옵션 단위 상태라 상품 상태와 별개다. 기존 주문에는 손대지 않는다. */
    public void changeStatus(SaleStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    /** 값 표시명이 바뀌면 조합 표시명도 따라간다. 규칙은 {@link OptionCombination#titleOf} 하나다. */
    public void retitle(String title) {
        this.title = Objects.requireNonNull(title, "title");
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public String getSku() {
        return sku;
    }

    public String getTitle() {
        return title;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public boolean isPriceOverridden() {
        return priceOverridden;
    }

    public String getFilterAttributes() {
        return filterAttributes;
    }

    public String getDisplayAttributes() {
        return displayAttributes;
    }

    public String getCombinationKey() {
        return combinationKey;
    }

    public SaleStatus getStatus() {
        return status;
    }
}
