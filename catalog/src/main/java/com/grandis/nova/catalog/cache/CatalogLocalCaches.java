package com.grandis.nova.catalog.cache;

import com.grandis.nova.catalog.category.CategoryNode;
import com.grandis.nova.catalog.listing.ProductListItem;
import com.grandis.nova.common.OffsetPage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;

/**
 * 인스턴스 메모리에 두는 공개 조회 캐시 — 상품 목록 · 카테고리 트리. 나머지(상세 본문 · 리뷰 · 홍보)는 Redis({@link CatalogReadCache})다.
 * 고른 기준(2026-10-16 실측 — Testcontainers MySQL 8.4 · Redis 8, p50): 목록 20개 한 쪽은 30.6KB · DB 4.2ms · Redis 적중 1.05ms · 메모리 적중 0.002ms,
 * 카테고리 트리는 4.7KB · DB 1.5ms · Redis 적중 0.40ms. 둘 다 다른 인스턴스가 늦어도 되는 조회다 — 목록은 TTL(5초)이 원래 지연이고, 카테고리는
 * 고치는 API 가 없다. 상세 본문 · 리뷰 · 홍보는 TTL 이 길거나 쓴 사람이 바로 봐야 해 모든 인스턴스를 같이 비우는 Redis 세대가 필요하다.
 * 무게는 JSON 바이트로 센다(넣을 때 한 번 직렬화한다) — 힙 점유가 아니라 Redis 판의 저장 상한과 같은 단위의 근사다.
 * 카테고리는 비우는 곳이 없다 — 마이그레이션이나 SQL 로 바꾸면 인스턴스마다 최대 TTL(10분) 뒤 또는 재기동 때 보인다(마이그레이션만 바뀐 배포는
 * catalog 를 다시 띄우지 않는다).
 */
@Component
public class CatalogLocalCaches {

    /** 목록 캐시의 인스턴스당 상한(JSON 바이트). 한 쪽이 약 30KB(20개 · 색상칩) — 약 500쪽. */
    static final long PRODUCT_LIST_MAX_BYTES = 16L * 1024 * 1024;
    /** 카테고리 트리는 키 하나(약 5KB). */
    static final long CATEGORY_TREE_MAX_BYTES = 1024 * 1024;

    private final LocalReadCache<CatalogReadCache.ListKey, OffsetPage<ProductListItem>> productList;
    private final LocalReadCache<String, List<CategoryNode>> categoryTree;

    /** nova.read-cache.enabled=false 면 끈다(늘 DB) — Redis 캐시와 같은 설정이다. */
    public CatalogLocalCaches(JsonMapper json, Clock clock, @Value("${nova.read-cache.enabled:true}") boolean enabled) {
        this.productList = new LocalReadCache<>("catalog.product-list", CatalogReadCache.PRODUCT_TTL, PRODUCT_LIST_MAX_BYTES,
                value -> json.writeValueAsBytes(value).length, clock, enabled);
        this.categoryTree = new LocalReadCache<>("catalog.category-tree", CatalogReadCache.CATEGORY_TTL, CATEGORY_TREE_MAX_BYTES,
                value -> json.writeValueAsBytes(value).length, clock, enabled);
    }

    public LocalReadCache<CatalogReadCache.ListKey, OffsetPage<ProductListItem>> productList() {
        return productList;
    }

    public LocalReadCache<String, List<CategoryNode>> categoryTree() {
        return categoryTree;
    }
}
