package com.grandis.nova.catalog.cache;

import com.grandis.nova.catalog.review.ProductReview;
import com.grandis.nova.common.redis.cache.ReadCache;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.springframework.stereotype.Component;

/**
 * 상품 · 옵션 · 리뷰 엔티티의 JPA 콜백(넣기 · 고치기 · 지우기)에서 읽기 캐시 세대를 올린다. 올리기는 커밋 뒤에 한 번이다({@link ReadCache#bumpAfterCommit}).
 * 서비스 메서드마다 부르지 않고 엔티티에 건다 — 쓰기 경로가 늘어도 빠지지 않게. catalog 의 상품 쓰기는 전부 JPA 를 거친다(JDBC 쓰기 없음).
 * 리뷰 노출은 상품 공개 여부를 따르므로 상품 · 옵션이 바뀌면 리뷰 세대도 올린다. 상품 목록(인스턴스 메모리)은 커밋 뒤 이 인스턴스의 것을 버린다 —
 * 다른 인스턴스는 목록 TTL(5초) 안에 새 값이다.
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
            cache.bumpAfterCommit(CatalogReadCache.PRODUCTS);
            local.productList().invalidateAfterCommit();
        }
        cache.bumpAfterCommit(CatalogReadCache.REVIEWS);
    }
}
