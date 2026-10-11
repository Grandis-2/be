package com.grandis.nova.catalog.review;

import com.grandis.nova.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * 상품 리뷰 — 배송 완료된 일반 판매 주문상품 1건당 1개(UNIQUE order_item_id). 글과 별점만 받는다.
 * 쓸 자격은 작성 때 order 내부 API 로 확인하고, 그때 받은 옵션명 · 가린 작성자명을 복사해 둔다 — catalog 는 order · member 표를 읽지 않는다.
 * 수정은 별점 · 본문만, 삭제는 행을 지운다(숨김 상태 · 수정 이력 없음).
 */
@Entity
@Table(name = "product_reviews")
public class ProductReview extends BaseEntity {

    public static final int MIN_RATING = 1;
    public static final int MAX_RATING = 5;
    /** body 칼럼(varchar(2000)) — 앞뒤 공백을 뺀 글자 수. */
    public static final int MAX_BODY_LENGTH = 2000;

    @Column(nullable = false, updatable = false)
    private UUID productId;

    @Column(nullable = false, updatable = false)
    private UUID customerId;

    @Column(nullable = false, updatable = false)
    private UUID orderItemId;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(nullable = false)
    private int rating;

    @Column(nullable = false, length = MAX_BODY_LENGTH)
    private String body;

    @Column(nullable = false, updatable = false, length = 120)
    private String optionTitleSnapshot;

    @Column(nullable = false, updatable = false, length = 10)
    private String authorName;

    protected ProductReview() {
    }

    private ProductReview(UUID productId, UUID customerId, UUID orderItemId, int rating, String body,
                          String optionTitleSnapshot, String authorName) {
        this.productId = productId;
        this.customerId = customerId;
        this.orderItemId = orderItemId;
        this.rating = requireRating(rating);
        this.body = requireBody(body);
        this.optionTitleSnapshot = optionTitleSnapshot;
        this.authorName = authorName;
    }

    public static ProductReview write(UUID productId, UUID customerId, UUID orderItemId, int rating, String body,
                                      String optionTitleSnapshot, String authorName) {
        return new ProductReview(productId, customerId, orderItemId, rating, body, optionTitleSnapshot, authorName);
    }

    /** 보낸 칸만 바꾼다(null 은 그대로). */
    public void revise(Integer rating, String body) {
        if (rating != null) {
            this.rating = requireRating(rating);
        }
        if (body != null) {
            this.body = requireBody(body);
        }
    }

    private static int requireRating(int rating) {
        if (rating < MIN_RATING || rating > MAX_RATING) {
            throw new IllegalArgumentException("rating must be " + MIN_RATING + ".." + MAX_RATING + ": " + rating);
        }
        return rating;
    }

    private static String requireBody(String body) {
        String stripped = body == null ? "" : body.strip();
        if (stripped.isEmpty() || stripped.length() > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException("body must be 1.." + MAX_BODY_LENGTH + " characters");
        }
        return stripped;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getOrderItemId() {
        return orderItemId;
    }
}
