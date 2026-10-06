package com.grandis.nova.catalog.image;

import com.grandis.nova.catalog.option.ProductOptionAxis;
import com.grandis.nova.catalog.option.ProductOptionValue;
import com.grandis.nova.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * 상품 사진 한 장. 묶음(kind + bundleKey) 안에서 position 이 순서고 대표는 하나다.
 *
 * bundleKey 는 GALLERY 면 그 상품 color 축 값의 정규화값(색상 없는 상품은 ""), DETAIL 이면 상세 영역 이름이다.
 * 엔티티는 상품의 축을 모르므로 GALLERY 팩토리가 color 축과 값을 직접 받아 소속을 검사한다 — 임의 문자열이 들어가면
 * 색상별 대표 조회가 빗나가는 별도 묶음이 생긴다.
 * primary_marker 는 DB 가 계산하는 칼럼이라 매핑하지 않는다 — 매핑하면 앱이 쓸 수 있는 자리가 생긴다.
 * DB 가 지키는 것은 묶음당 대표 ≤ 1 뿐이다. "사진이 있으면 대표가 있다" 와 대표 삭제 뒤 첫 사진 자동 대표는 앱 책임이다.
 * GALLERY 묶음당 10장 상한은 앱 검사다(DETAIL 에는 상한 결정이 없다).
 * UNIQUE(position) 은 행마다 검사된다(MySQL 8.4.11 실측). 두 장의 순서 교환은 한 UPDATE 로 안 된다(임시 오프셋 두 단계나
 * 지우고 다시 넣기). 한 방향 이동은 되지만 ORDER BY 를 빼면 성패가 행 순서에 달리니 항상 명시한다 — 밀기는 position DESC, 당기기는 ASC.
 */
@Entity
@Table(name = "product_images")
public class ProductImage extends BaseEntity {

    /** 색상 없는 상품의 GALLERY 묶음 키. NULL 이면 UNIQUE 가 중복을 못 막아 빈 문자열이다. */
    public static final String DEFAULT_BUNDLE = "";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private ImageKind kind;

    @Column(nullable = false, updatable = false, length = 60)
    private String bundleKey;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, length = 1000)
    private String url;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    protected ProductImage() {
    }

    private ProductImage(Long productId, ImageKind kind, String bundleKey, int position, String url, boolean primary) {
        if (position < 0) {
            throw new IllegalArgumentException("position must be zero or positive: " + position);
        }
        if (bundleKey == null) {
            throw new IllegalArgumentException("bundleKey must not be null");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url must not be blank");
        }
        this.productId = productId;
        this.kind = kind;
        this.bundleKey = bundleKey;
        this.position = position;
        this.url = url;
        this.primary = primary;
    }

    /**
     * 상품 사진. 묶음은 color 축의 값 하나다. colorValue 가 null 이면 색상 없는 상품의 기본 묶음('')이다.
     * 축이 이 상품의 color 축이고 값이 그 축의 값인지 검사한다.
     */
    public static ProductImage gallery(Long productId, ProductOptionAxis colorAxis, ProductOptionValue colorValue,
                                       int position, String url, boolean primary) {
        String bundleKey = DEFAULT_BUNDLE;
        if (colorValue != null) {
            if (colorAxis == null || !ProductOptionAxis.COLOR.equals(colorAxis.getAxisKey())
                    || !Objects.equals(colorAxis.getProductId(), productId)
                    || !Objects.equals(colorValue.getAxisId(), colorAxis.getId())) {
                throw new IllegalArgumentException("bundle must be a color value of product " + productId);
            }
            bundleKey = colorValue.getNormalizedValue();
        }
        return new ProductImage(productId, ImageKind.GALLERY, bundleKey, position, url, primary);
    }

    /** 상세 콘텐츠 이미지. 묶음은 영역 이름이고 값과 같은 규칙(NFC · 트림 · 공백 접기)으로 정규화한다. */
    public static ProductImage detail(Long productId, String section, int position, String url, boolean primary) {
        return new ProductImage(productId, ImageKind.DETAIL, ProductOptionValue.normalize(section), position, url, primary);
    }

    /**
     * 대표 지정 · 해제. 묶음당 하나는 DB UNIQUE 가 지키므로 교체는 반드시 "이전 대표 해제 → flush → 새 대표 지정" 순서다.
     * 해제와 지정을 한 flush 에 묶으면 UPDATE 순서가 호출 순서가 아니라 영속성 컨텍스트 적재 순서라서,
     * 새 대표가 먼저 적재돼 있으면 지정이 먼저 나가 1062 로 터진다(실측 — 조회 순서에 따라 붙었다 떨어졌다 한다).
     */
    public void markPrimary(boolean primary) {
        this.primary = primary;
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public ImageKind getKind() {
        return kind;
    }

    public String getBundleKey() {
        return bundleKey;
    }

    public int getPosition() {
        return position;
    }

    public String getUrl() {
        return url;
    }

    public boolean isPrimary() {
        return primary;
    }
}
