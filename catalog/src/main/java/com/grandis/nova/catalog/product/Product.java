package com.grandis.nova.catalog.product;

import com.grandis.nova.catalog.option.ProductOptions;
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
import java.time.Instant;
import java.util.Objects;

/**
 * 상품(모델). 옵션은 {@link ProductOption} 이 product id 로 잇는다.
 *
 * 노출은 세 가지가 함께 정한다 — visible · status · 판매 방식별 준비(사전예약은 preorder 회차 행, 일반은 order 재고 행).
 * 준비는 다른 서비스가 등록 이벤트를 받아 만든 행이라 catalog 가 칸으로 들고 있지 않고, 노출을 읽는 쿼리가 함께 본다
 * ({@link com.grandis.nova.catalog.listing.ProductListingQueryRepository}). 그래서 visible 은 관리자가 고른 값을 등록 때 바로 담는다 —
 * 준비가 안 된 상품은 visible 이어도 회원에게 보이지 않는다.
 * 가격은 basePrice 가 기준이고 옵션의 price 가 최종가다(기본가 + 고른 값의 추가금 합 — 조합별 수동 가격은 없다).
 * 옵션 축 · 값 · 사진 · 보증은 {@link #getOptions() options}(JSON) 한 칸이다({@link ProductOptions}). 썸네일은 그 문서에서 계산해 같이 저장한다.
 * 예약 · 주문은 접수 시점 값을 복사하므로 여기를 고쳐도 과거 거래에 소급되지 않는다.
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

    /** 관리자 등록의 Idempotency-Key. 같은 키로 다시 오면 이 상품을 돌려준다. 등록 API 밖에서 들어온 행(다른 모듈 픽스처)은 null. */
    @Column(updatable = false, length = 100)
    private String idempotencyKey;

    @Column(nullable = false)
    private Long categoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private SaleMode saleMode;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false)
    private BigDecimal basePrice;

    /** 옵션 축 · 값 · 추가금 · 색상 hex · 사진 · 보증({@link ProductOptions}). 비면 "{}". */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String options;

    /** 목록 · 카드 썸네일 — {@link ProductOptions#thumbnailUrl()} 를 options 와 같이 쓴다. 사진이 없으면 null. */
    @Column(length = 1000)
    private String thumbnailUrl;

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

    /** 사전예약 회차 취소를 접수한 시각. null 이면 취소 아님 — 오픈 전에 판매 중지로 둔 채 오픈을 넘긴 상품도 null 이다(2026-10-04 결정). */
    private Instant campaignCanceledAt;

    protected Product() {
    }

    private Product(String idempotencyKey, Long categoryId, SaleMode saleMode, String title, BigDecimal basePrice,
                    String description, String tags, boolean visible, ProductOptions options) {
        this.idempotencyKey = idempotencyKey;
        this.categoryId = categoryId;
        this.saleMode = saleMode;
        this.title = title;
        this.basePrice = Amounts.requireWholeWon(basePrice, "basePrice");
        this.description = description;
        this.tags = tags;
        this.status = SaleStatus.ACTIVE;
        this.visible = visible;
        replaceOptions(options);
    }

    /**
     * 새 상품. 판매 상태는 ACTIVE, 공개 여부는 관리자가 고른 값이다 — 판매 방식별 준비가 끝나기 전에는 visible 이어도 노출되지 않는다.
     * 보증을 제공하지 않으면 추가금은 0 이다.
     */
    public static Product register(String idempotencyKey, Long categoryId, SaleMode saleMode, String title, BigDecimal basePrice,
                                   String description, String tags, boolean visible,
                                   boolean warrantyOffered, BigDecimal warrantySurcharge, ProductOptions options) {
        return new Product(idempotencyKey, categoryId, saleMode, title, basePrice, description, tags, visible,
                Objects.requireNonNull(options, "options").withWarranty(warrantyOf(warrantyOffered, warrantySurcharge, "warrantySurcharge")));
    }

    /** 옵션 문서를 바꾼다. 썸네일도 같은 문서에서 다시 계산한다 — 따로 쓰면 둘이 어긋난다. */
    public void replaceOptions(ProductOptions next) {
        ProductOptions document = Objects.requireNonNull(next, "options");
        this.options = document.toJson();
        this.thumbnailUrl = document.thumbnailUrl();
    }

    public ProductOptions getOptions() {
        return ProductOptions.parse(options);
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
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

    /**
     * 사전예약 회차 취소 — 판매 중지로 두고 취소 시각을 남긴다. 되돌릴 수 없다(DB CHECK 가 취소 표식이 있는 행의 ACTIVE 를 막는다).
     * 오픈 뒤인지는 서비스가 본다(회차는 preorder 표).
     *
     * @return 이번에 새로 취소했으면 true. 이미 취소된 상품이면 false — 취소 이벤트를 다시 적지 않는다
     */
    public boolean cancelCampaign(Instant now) {
        if (saleMode != SaleMode.PREORDER) {
            throw new IllegalStateException("회차 취소는 사전예약 상품만: " + id);
        }
        if (campaignCanceledAt != null) {
            return false;
        }
        this.status = SaleStatus.PAUSED;
        this.campaignCanceledAt = Objects.requireNonNull(now, "now");
        return true;
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

    /** 기본 가격. 바뀌었으면 true — 호출자가 모든 옵션 가격을 재계산한다. */
    public boolean reprice(BigDecimal basePrice) {
        BigDecimal next = Amounts.requireWholeWon(basePrice, "basePrice");
        if (this.basePrice.compareTo(next) == 0) {
            return false;
        }
        this.basePrice = next;
        return true;
    }

    /** 보증 설정 — 옵션 문서의 warranty 를 바꾼다. 제공하지 않으면 추가금은 0 이다(등록과 같은 규칙). */
    public void setWarranty(boolean offered, BigDecimal surcharge) {
        ProductOptions document = getOptions();
        ProductOptions.Warranty next = warrantyOf(offered, surcharge, "warranty.surcharge");
        if (!next.equals(document.warranty())) {   // 같은 값을 다시 보내면 문서를 다시 쓰지 않는다(UPDATE · updated_at 이 안 난다)
            replaceOptions(document.withWarranty(next));
        }
    }

    /** 제공하면 추가금을 정수 원으로 검사하고, 제공하지 않으면 추가금을 보지 않는다(0). */
    private static ProductOptions.Warranty warrantyOf(boolean offered, BigDecimal surcharge, String name) {
        return offered ? new ProductOptions.Warranty(true, Amounts.requireWholeWon(surcharge, name)) : ProductOptions.Warranty.NONE;
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

    public Instant getCampaignCanceledAt() {
        return campaignCanceledAt;
    }

    public boolean isVisible() {
        return visible;
    }

    public boolean isWarrantyOffered() {
        return getOptions().warranty().offered();
    }

    public BigDecimal getWarrantySurcharge() {
        return getOptions().warranty().surcharge();
    }
}
