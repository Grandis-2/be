package com.grandis.nova.catalog.cache;

import com.grandis.nova.catalog.category.CategoryNode;
import com.grandis.nova.catalog.listing.ProductListItem;
import com.grandis.nova.common.OffsetPage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 인스턴스 메모리에 두는 공개 조회 캐시 — 상품 목록 · 조회수 랭킹 · 카테고리 트리 · 홍보 카드. 나머지(상세 본문 · 리뷰 · 홍보 내용)는 Redis({@link CatalogReadCache})다.
 * 고른 기준(2026-10-16 실측 — Testcontainers MySQL 8.4 · Redis 8, p50): 목록 20개 한 쪽은 30.6KB · DB 4.2ms · Redis 적중 1.05ms · 메모리 적중 0.002ms,
 * 카테고리 트리는 4.7KB · DB 1.5ms · Redis 적중 0.40ms. 둘 다 다른 인스턴스가 늦어도 되는 조회다 — 목록은 TTL(5초)이 원래 지연이고, 카테고리는
 * 고치는 API 가 없다. 홍보 카드는 목록과 같은 카드(가격 · 판매 준비 · 색상칩)이고 같은 TTL 이다 — 2026-10-22 부하 시험에서 홍보 상세가 한 태스크(1 vCPU)에
 * 몰려 CPU 가 찼고, 요청마다 읽던 카드 두 문장과 그 매핑의 몫이 컸다(그 밖에 막 뜬 JVM 의 JIT · GC). 로컬 재현(CPU 0.5 · 70 req/s · 3회)에서 카드를
 * Redis 값으로 5초 두면 세 번 모두 무너지지 않았다(카드 캐시 없는 쪽은 세 번 모두 무너짐). 다만 그중 두 번은 Redis 시간 초과 때 카드도 같이 우회돼 p99 가
 * 2초대로 남았다. 메모리에 두면 꺼낼 때 Redis 값 읽기 · JSON 변환이 없다.
 * 숨김 · 판매 중지 · 삭제는 모든 인스턴스에서 바로 빠져야 해(사용자 결정 2026-10-22) 키에 상품 세대 번호를 넣는다 — 상품 상세와 같은 세대라 상품이
 * 바뀌면 모든 인스턴스가 다음 조회부터 새로 읽는다. 그래서 요청마다 세대 읽기(Redis GET 하나)는 남는다. 세대를 못 읽으면(Redis 실패) 마지막으로 본 세대의
 * 카드를 TTL 안에서 그대로 쓰고(장애 중 숨김은 최대 TTL 늦음 — 사용자 결정 2026-10-22), 없을 때만 넣지 않고 DB 로 읽되 같은 키의 동시 조회는 한 번으로
 * 합친다. 이 설계(세대 키 · 장애 중 마지막 세대)는 위 재현으로 재지 않았다 — 위 수치는 Redis 값 캐시의 것이다.
 * 상세 본문 · 리뷰 · 홍보 내용은 TTL 이 길거나 쓴 사람이 바로 봐야 해 모든 인스턴스를 같이 비우는 Redis 세대가 필요하다.
 * 무게는 JSON 바이트로 센다(넣을 때 한 번 직렬화한다) — 힙 점유가 아니라 Redis 판의 저장 상한과 같은 단위의 근사다.
 * 카테고리는 비우는 곳이 없다 — 마이그레이션이나 SQL 로 바꾸면 인스턴스마다 최대 TTL(10분) 뒤 또는 재기동 때 보인다(마이그레이션만 바뀐 배포는
 * catalog 를 다시 띄우지 않는다).
 */
@Component
public class CatalogLocalCaches {

    /** 목록 캐시의 인스턴스당 상한(JSON 바이트). 한 쪽이 약 30KB(20개 · 색상칩) — 약 500쪽. */
    static final long PRODUCT_LIST_MAX_BYTES = 16L * 1024 * 1024;
    /** 조회수 랭킹은 키 하나 · 카드 10개(약 15KB). */
    static final long PRODUCT_RANKING_MAX_BYTES = 1024 * 1024;
    /** 조회수 랭킹을 메모리에 두는 시간 — 메인 화면마다 Redis 합치기 · DB 카드 조회를 하지 않게. 상품이 바뀌면 이 인스턴스의 것은 바로 버린다. */
    public static final Duration PRODUCT_RANKING_TTL = Duration.ofSeconds(30);
    /** 카테고리 트리는 키 하나(약 5KB). */
    static final long CATEGORY_TREE_MAX_BYTES = 1024 * 1024;
    /** 홍보 카드의 인스턴스당 상한(JSON 바이트). 홍보 하나가 상품 20개까지다 — 상품 목록 한 쪽(카드 20개 · 색상칩, 10-16 실측 30.6KB) 기준으로 약 130개 근사. */
    static final long PROMOTION_CARDS_MAX_BYTES = 4L * 1024 * 1024;

    private final LocalReadCache<CatalogReadCache.ListKey, OffsetPage<ProductListItem>> productList;
    private final LocalReadCache<String, List<CategoryNode>> categoryTree;
    private final LocalReadCache<String, List<ProductListItem>> productRanking;
    private final LocalReadCache<CardsKey, List<ProductListItem>> promotionCards;

    /**
     * nova.read-cache.enabled=false 면 끈다(늘 DB) — Redis 캐시와 같은 설정이다. 미리 갱신은 {@link ReadCacheRefresher} 가 맡는다(크기를 묶은 실행기) —
     * {@link LocalReadCache} 참고.
     */
    public CatalogLocalCaches(JsonMapper json, Clock clock, @Value("${nova.read-cache.enabled:true}") boolean enabled, ReadCacheRefresher refresher,
                              ReadCacheMetrics metrics) {
        this.productList = new LocalReadCache<>("catalog.product-list", CatalogReadCache.PRODUCT_TTL, PRODUCT_LIST_MAX_BYTES,
                value -> json.writeValueAsBytes(value).length, clock, enabled, refresher, metrics);
        this.categoryTree = new LocalReadCache<>("catalog.category-tree", CatalogReadCache.CATEGORY_TTL, CATEGORY_TREE_MAX_BYTES,
                value -> json.writeValueAsBytes(value).length, clock, enabled, refresher, metrics);
        this.productRanking = new LocalReadCache<>("catalog.product-ranking", PRODUCT_RANKING_TTL, PRODUCT_RANKING_MAX_BYTES,
                value -> json.writeValueAsBytes(value).length, clock, enabled, refresher, metrics);
        this.promotionCards = new LocalReadCache<>("catalog.promotion-cards", CatalogReadCache.PRODUCT_TTL, PROMOTION_CARDS_MAX_BYTES,
                value -> json.writeValueAsBytes(value).length, clock, enabled, refresher, metrics);
    }

    public LocalReadCache<CatalogReadCache.ListKey, OffsetPage<ProductListItem>> productList() {
        return productList;
    }

    public LocalReadCache<String, List<CategoryNode>> categoryTree() {
        return categoryTree;
    }

    public LocalReadCache<String, List<ProductListItem>> productRanking() {
        return productRanking;
    }

    public LocalReadCache<CardsKey, List<ProductListItem>> promotionCards() {
        return promotionCards;
    }

    /**
     * 홍보 카드의 키 — 묶은 상품(보이는 순서) · 색상칩 여부 · 상품 세대 번호. 홍보에 묶은 상품이 바뀌면 키가 바뀌므로 홍보 쪽 비우기가 따로 필요 없고,
     * 상품이 바뀌면(세대가 오르면) 모든 인스턴스에서 키가 바뀐다. 옛 세대의 값은 아무도 읽지 않고 TTL 로 사라진다.
     *
     * @param productIds         복사해 고정한다(넘긴 목록이 바뀌어도 키는 그대로)
     * @param productsGeneration {@link CatalogReadCache#PRODUCTS} 세대의 지금 번호({@link com.grandis.nova.common.redis.cache.ReadCache#generation})
     */
    public record CardsKey(List<UUID> productIds, boolean withColors, long productsGeneration) {

        /** 세대를 못 읽었을 때의 자리 — 이 키로는 넣지 않고 같은 키의 동시 조회만 합친다. 실제 세대 번호는 0 이상이라 겹치지 않는다. */
        public static final long UNREADABLE = Long.MIN_VALUE;

        public CardsKey {
            productIds = List.copyOf(productIds);
        }
    }
}
