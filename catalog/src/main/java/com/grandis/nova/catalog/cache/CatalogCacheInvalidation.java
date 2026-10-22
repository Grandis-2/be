package com.grandis.nova.catalog.cache;

import com.grandis.nova.catalog.review.ProductReview;
import com.grandis.nova.common.redis.cache.ReadCache;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.springframework.stereotype.Component;

/**
 * 상품 · 옵션 · 리뷰 엔티티의 JPA 콜백(넣기 · 고치기 · 지우기)에서 읽기 캐시 세대를 올리고 메모리의 상품 목록 · 조회수 랭킹을 버린다. 둘 다 커밋 뒤에 한 번이다
 * ({@link ReadCache#bumpAfterCommit} · {@link LocalReadCache#invalidateAfterCommit}).
 * 서비스 메서드마다 부르지 않고 엔티티에 건다 — 쓰기 경로가 늘어도 빠지지 않게. 엔티티를 거치지 않는 상품 쓰기(판매 준비 처리기의 JDBC 갱신)는
 * {@link #productsChanged} 를 직접 부른다 — JDBC 쓰기를 더하면 같이 부른다.
 * 리뷰 노출은 상품 공개 여부를 따르므로 상품 · 옵션이 바뀌면 리뷰 세대도 올린다. 상품 목록 · 랭킹(인스턴스 메모리)은 커밋 뒤 이 인스턴스의 것을
 * 버린다 — 다른 인스턴스는 목록 TTL(5초) · 랭킹 TTL(30초) 안에 새 값이다. 엔티티 변경이 아닌 것(시각이 지나 사전예약이 마감 · 접수 시작, 다른 서비스의
 * 재고 · 회차 표 변경)은 TTL 로만 따라간다.
 */
@Component
public class CatalogCacheInvalidation {

    private final ReadCache cache;
    private final CatalogLocalCaches local;

    public CatalogCacheInvalidation(ReadCache cache, CatalogLocalCaches local) {
        this.cache = cache;
        this.local = local;
    }

    @PostPersist
    @PostUpdate
    @PostRemove
    void changed(Object entity) {
        if (!(entity instanceof ProductReview)) {
            productsChanged();
            return;
        }
        cache.bumpAfterCommit(CatalogReadCache.REVIEWS);
    }

    /**
     * 상품의 노출이 바뀌었다 — 엔티티를 거치지 않는 쓰기(받은 이벤트의 JDBC 갱신)도 부른다. 상품 · 리뷰 세대를 올리고 이 인스턴스의 목록 · 랭킹을
     * 버린다. 커밋 뒤에 한 번이다 — 트랜잭션 안에서 부른다.
     */
    public void productsChanged() {
        cache.bumpAfterCommit(CatalogReadCache.PRODUCTS);
        local.productList().invalidateAfterCommit();
        local.productRanking().invalidateAfterCommit();
        cache.bumpAfterCommit(CatalogReadCache.REVIEWS);
    }
}
