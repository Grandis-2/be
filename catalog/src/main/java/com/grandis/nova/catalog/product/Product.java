package com.grandis.nova.catalog.product;

import com.grandis.nova.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 상품(모델). 옵션은 {@link ProductOption} 이 product id 로 잇는다.
 *
 * 노출은 세 가지가 함께 정한다 — visible · status · 판매 방식별 준비(사전예약은 preorder 회차 행, 일반은 order 재고 행).
 * 준비는 다른 서비스가 등록 이벤트를 받아 만든 행이라 catalog 가 칸으로 들고 있지 않고, 노출을 읽는 쿼리가 함께 본다
 * ({@link com.grandis.nova.catalog.listing.ProductListingQueryRepository}). 그래서 visible 은 관리자가 고른 값을 등록 때 바로 담는다 —
 * 준비가 안 된 상품은 visible 이어도 회원에게 보이지 않는다.
 * 가격은 basePrice 가 기준이고 옵션의 price 가 최종가다(기본가 + 값별 추가금, 관리자가 직접 고칠 수 있다).
 * 예약 · 주문은 접수 시점 값을 복사하므로 여기를 고쳐도 과거 거래에 소급되지 않는다.
 * image_url 은 product_images 의 GALLERY 대표로 대체돼 폐기 예정이라 매핑하지 않는다.
 *
 * <p><b>이미 있는 상품을 고치는 경로는 전부 {@link ProductRepository#findForUpdate} 로 잠그고 읽는다</b>(공개 전환 · 판매 상태 포함).
 * Hibernate 는 전 칼럼을 UPDATE 하므로 잠그지 않고 읽은 쓰기는 그사이 커밋된 관리자 수정(제목 · 기본가)을 옛 값으로 되돌리고, 재계산된
 * 옵션 가격만 새 값으로 남아 "가격 = 기본가 + Σ추가금" 이 깨진다(실측). 새 쓰기 경로는 "잠그지 않고 읽기 → 수정 커밋 → 쓰기" 모양의 시험을 같이 둔다.
 */
@Entity
@Table(name = "products")
public class Product extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long categoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private SaleMode saleMode;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false)
    private BigDecimal basePrice;

    @Column(columnDefinition = "text")
    private String description;

    /** 관리자 검색 키워드. 목록 검색은 상품명과 이 칸을 부분 일치로 본다. */
    @Column(length = 500)
    private String tags;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SaleStatus status;

    @Column(nullable = false)
    private boolean visible;

    @Column(nullable = false)
    private boolean warrantyOffered;

    @Column(nullable = false)
    private BigDecimal warrantySurcharge;

    protected Product() {
    }

    private Product(Long categoryId, SaleMode saleMode, String title, BigDecimal basePrice, String description,
                    String tags, boolean visible, boolean warrantyOffered, BigDecimal warrantySurcharge) {
        this.categoryId = categoryId;
        this.saleMode = saleMode;
        this.title = title;
        this.basePrice = Amounts.requireWholeWon(basePrice, "basePrice");
        this.description = description;
        this.tags = tags;
        this.status = SaleStatus.ACTIVE;
        this.visible = visible;
        this.warrantyOffered = warrantyOffered;
        this.warrantySurcharge = Amounts.requireWholeWon(warrantySurcharge, "warrantySurcharge");
    }

    /**
     * 새 상품. 판매 상태는 ACTIVE, 공개 여부는 관리자가 고른 값이다 — 판매 방식별 준비가 끝나기 전에는 visible 이어도 노출되지 않는다.
     * 보증을 제공하지 않으면 추가금은 0 이다.
     */
    public static Product register(Long categoryId, SaleMode saleMode, String title, BigDecimal basePrice,
                                   String description, String tags, boolean visible,
                                   boolean warrantyOffered, BigDecimal warrantySurcharge) {
        return new Product(categoryId, saleMode, title, basePrice, description, tags, visible,
                warrantyOffered, warrantyOffered ? warrantySurcharge : BigDecimal.ZERO);
    }

    /** 공개. 조건 없다 — 준비가 안 된 상품은 공개여도 노출 쿼리가 거른다. */
    public void publish() {
        this.visible = true;
    }

    /** 비공개. 조건 없다. 기존 예약 · 주문에는 손대지 않는다. */
    public void hide() {
        this.visible = false;
    }

    /**
     * 판매 시작 · 중지(ACTIVE ↔ PAUSED). 판매 중지는 회원 목록에서 빠지고 상세에는 판매 중지로 보인다. 신규 접수 · 주문은 이 상태를 읽는
     * 쪽(preorder 접수 · order 주문)이 막는다 — catalog 는 상태만 바꾸고 기존 예약 · 주문에는 손대지 않는다.
     * 사전예약은 오픈 3분 전부터 못 바꾼다는 규칙은 서비스가 지킨다(회차는 preorder 표).
     */
    public void changeStatus(SaleStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    /** 표시 정보 수정. null 은 "보내지 않음" 이라 그대로 둔다. 사전예약 오픈 뒤 금지는 서비스가 지킨다(회차는 preorder 표). */
    public void edit(String title, String description, String tags) {
        if (title != null) {
            this.title = title.strip();
        }
        if (description != null) {
            this.description = description;
        }
        if (tags != null) {
            this.tags = tags;
        }
    }

    /** 기본 가격. 바뀌었으면 true — 호출자가 수동 가격이 아닌 옵션을 재계산한다(설계 §2.1 재계산). */
    public boolean reprice(BigDecimal basePrice) {
        BigDecimal next = Amounts.requireWholeWon(basePrice, "basePrice");
        if (this.basePrice.compareTo(next) == 0) {
            return false;
        }
        this.basePrice = next;
        return true;
    }

    /** 보증 설정. 제공하지 않으면 추가금은 0 이다(등록과 같은 규칙). */
    public void setWarranty(boolean offered, BigDecimal surcharge) {
        this.warrantyOffered = offered;
        this.warrantySurcharge = offered ? Amounts.requireWholeWon(surcharge, "warranty.surcharge") : BigDecimal.ZERO;
    }

    public Long getId() {
        return id;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public SaleMode getSaleMode() {
        return saleMode;
    }

    public String getTitle() {
        return title;
    }

    public BigDecimal getBasePrice() {
        return basePrice;
    }

    public String getDescription() {
        return description;
    }

    public String getTags() {
        return tags;
    }

    public SaleStatus getStatus() {
        return status;
    }

    public boolean isVisible() {
        return visible;
    }

    public boolean isWarrantyOffered() {
        return warrantyOffered;
    }

    public BigDecimal getWarrantySurcharge() {
        return warrantySurcharge;
    }
}
