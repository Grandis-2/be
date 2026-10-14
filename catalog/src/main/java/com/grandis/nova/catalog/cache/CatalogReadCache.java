package com.grandis.nova.catalog.cache;

import com.grandis.nova.catalog.category.CategoryNode;
import com.grandis.nova.catalog.category.CategoryTreeService;
import com.grandis.nova.catalog.detail.ProductDetailService;
import com.grandis.nova.catalog.detail.ProductDetailView;
import com.grandis.nova.catalog.listing.ProductListFilter;
import com.grandis.nova.catalog.listing.ProductListItem;
import com.grandis.nova.catalog.listing.ProductListingService;
import com.grandis.nova.catalog.listing.ProductSort;
import com.grandis.nova.catalog.review.ReviewService;
import com.grandis.nova.catalog.review.ReviewView;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.redis.cache.ReadCache;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 회원 공개 조회(로그인 없이 보는 상품 목록 · 상세 · 옵션 상세 · 카테고리 · 리뷰)의 읽기 캐시. 관리자 · 내부 조회(장바구니 · 접수 가격)는 거치지 않는다.
 *
 * <ul>
 *   <li><b>catalog 가 바꾸는 것</b>(등록 · 수정 · 공개 전환 · 판매 상태 · 사진 · 옵션 · 리뷰)은 커밋 뒤 세대를 올려 다음 조회부터 새 값이다
 *       ({@link CatalogCacheInvalidation}).</li>
 *   <li><b>다른 서비스가 바꾸는 것</b>(판매 준비 완료 — order 재고 행 · preorder 회차 행, 회차 시각 · 조기 마감, 재고 수량)은 catalog 가 알 수 없어
 *       TTL 이 지나야 보인다 — 그래서 상품 목록 · 상세의 TTL 은 짧다. 주문 · 접수는 이 캐시를 거치지 않고 다시 확인한다.</li>
 *   <li><b>시각으로 정하는 칸</b>(사전예약 접수 단계)은 꺼낼 때 지금 시각으로 다시 계산한다.</li>
 *   <li><b>앞단 HTTP 캐시</b>는 돌려준 {@link ReadCache.Entry#loadedAt()} 로 남은 시간만큼만 허용한다(PublicCache) — 두 단계를 합쳐도 TTL 을 넘지
 *       않게.</li>
 * </ul>
 * Redis 가 실패하면 캐시 없이 DB 로 답한다({@link ReadCache}).
 */
@Component
public class CatalogReadCache {

    /** 상품 · 옵션이 바뀌면 올리는 세대. 상품 목록 · 상세 · 옵션 상세가 쓴다. */
    static final String PRODUCTS = "products";
    /** 리뷰가 바뀌거나 상품이 바뀌면(공개 여부가 리뷰 노출을 정한다) 올리는 세대. */
    static final String REVIEWS = "reviews";
    /** 올리는 곳이 없다 — 카테고리는 운영 초기 데이터로만 바뀐다(관리 API 없음). TTL 이 끝낸다. */
    static final String CATEGORIES = "categories";

    public static final Duration PRODUCT_TTL = Duration.ofSeconds(5);
    public static final Duration REVIEW_TTL = Duration.ofSeconds(60);
    public static final Duration CATEGORY_TTL = Duration.ofMinutes(10);
    /** 목록 · 리뷰는 앞 페이지(0~4)만 Redis 에 넣는다. */
    static final int CACHED_PAGES = 5;
    /** 화면이 쓰는 상품 목록 size(추천 4 · 랭킹 10 · 목록 · 검색 12 · 기본 20). 다른 size 는 넣지 않는다. */
    static final Set<Integer> CACHED_PRODUCT_SIZES = Set.of(4, 10, 12, 20);
    /** 화면이 쓰는 리뷰 size(목록 100 · 기본 20). */
    static final Set<Integer> CACHED_REVIEW_SIZES = Set.of(20, 100);

    private final ReadCache cache;
    private final JsonMapper json;
    private final Clock clock;
    private final ProductDetailService details;
    private final ProductListingService listings;
    private final CategoryTreeService categories;
    private final ReviewService reviews;

    private final ReadCache.Spec<ProductDetailView> detailSpec;
    private final ReadCache.Spec<OffsetPage<ProductListItem>> listSpec;
    private final ReadCache.Spec<List<CategoryNode>> categorySpec;
    private final ReadCache.Spec<OffsetPage<ReviewView>> reviewSpec;

    public CatalogReadCache(ReadCache cache, JsonMapper json, Clock clock, ProductDetailService details, ProductListingService listings,
                            CategoryTreeService categories, ReviewService reviews) {
        this.cache = cache;
        this.json = json;
        this.clock = clock;
        this.details = details;
        this.listings = listings;
        this.categories = categories;
        this.reviews = reviews;
        this.detailSpec = cache.spec("product-detail", ProductDetailView.class, PRODUCT_TTL);
        this.listSpec = cache.spec("product-list", new TypeReference<OffsetPage<ProductListItem>>() { }, PRODUCT_TTL);
        this.categorySpec = cache.spec("category-tree", new TypeReference<List<CategoryNode>>() { }, CATEGORY_TTL);
        this.reviewSpec = cache.spec("reviews", new TypeReference<OffsetPage<ReviewView>>() { }, REVIEW_TTL);
    }

    public ReadCache.Entry<ProductDetailView> product(UUID productId) {
        ReadCache.Entry<ProductDetailView> entry = cache.entry(detailSpec, PRODUCTS, productId.toString(), () -> details.findProduct(productId));
        return new ReadCache.Entry<>(entry.value().at(clock.instant()), entry.loadedAt());
    }

    /** 옵션 상세 — 상품 상세(같은 캐시)에서 꺼낸다. 그 상품의 옵션이 아니면 404(상세 서비스와 같은 판정). */
    public ReadCache.Entry<ProductDetailView.Variant> variant(UUID productId, UUID variantId) {
        ReadCache.Entry<ProductDetailView> product = product(productId);
        ProductDetailView.Variant variant = product.value().variants().stream()
                .filter(candidate -> candidate.variantId().equals(variantId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        return new ReadCache.Entry<>(variant, product.loadedAt());
    }

    /**
     * 상품 목록. Redis 에 넣는 것은 입력이 닫힌 조회뿐이다 — 자유 문자열(검색어 q · 색상 · 용량)이 없고, 앞 {@link #CACHED_PAGES} 페이지이고,
     * size 가 화면이 쓰는 값({@link #CACHED_PRODUCT_SIZES})이고, 결과가 비지 않았다(없는 카테고리 id 는 빈 목록이라 넣지 않는다). 나머지는 늘 DB 다.
     * 입력을 아무렇게나 바꿔 키를 끝없이 만들어 나눠 쓰는 Redis 의 다른 키(토큰 폐기 표식 · 대기열)를 밀어내지 않게. 넣는 양 자체도 캐시마다
     * 메모리 예산이 묶는다(ReadCache).
     */
    public ReadCache.Entry<OffsetPage<ProductListItem>> products(ProductListFilter filter, ProductSort sort, int page, int size) {
        boolean closedInput = filter.q() == null && filter.colors().isEmpty() && filter.storages().isEmpty()
                && page < CACHED_PAGES && CACHED_PRODUCT_SIZES.contains(size);
        if (!closedInput) {
            return cache.uncached(() -> listings.list(filter, sort, page, size));
        }
        ProductListFilter keyed = new ProductListFilter(null, filter.categoryId(), filter.saleMode(),
                filter.brands().stream().sorted().toList(), List.of(), List.of());   // OR 조건은 순서를 맞춰 같은 조건이 같은 키
        ReadCache.Entry<OffsetPage<ProductListItem>> entry = cache.entry(listSpec, PRODUCTS, digest(new ListKey(keyed, sort, page, size)),
                () -> listings.list(filter, sort, page, size), found -> !found.items().isEmpty());
        Instant served = clock.instant();
        return new ReadCache.Entry<>(entry.value().map(item -> item.at(served)), entry.loadedAt());
    }

    public ReadCache.Entry<List<CategoryNode>> categoryTree() {
        return cache.entry(categorySpec, CATEGORIES, "tree", categories::tree);
    }

    /** 상품 리뷰. 앞 페이지 · 화면이 쓰는 size · 비지 않은 결과만 넣는다(없는 상품 id 는 빈 목록이라 넣지 않는다). */
    public ReadCache.Entry<OffsetPage<ReviewView>> productReviews(UUID productId, int page, int size) {
        if (page >= CACHED_PAGES || !CACHED_REVIEW_SIZES.contains(size)) {
            return cache.uncached(() -> reviews.productReviews(productId, page, size));
        }
        return cache.entry(reviewSpec, REVIEWS, "product:" + productId + ":" + page + ":" + size,
                () -> reviews.productReviews(productId, page, size), found -> !found.items().isEmpty());
    }

    /** 구매후기 모아보기. 상품 리뷰와 같은 규칙(없는 카테고리 id 는 빈 목록이라 넣지 않는다). */
    public ReadCache.Entry<OffsetPage<ReviewView>> visibleReviews(UUID categoryId, int page, int size) {
        if (page >= CACHED_PAGES || !CACHED_REVIEW_SIZES.contains(size)) {
            return cache.uncached(() -> reviews.visibleReviews(categoryId, page, size));
        }
        return cache.entry(reviewSpec, REVIEWS, "visible:" + categoryId + ":" + page + ":" + size,
                () -> reviews.visibleReviews(categoryId, page, size), found -> !found.items().isEmpty());
    }

    /** 목록 조건의 키. 조건은 정규화된 값(ProductListFilter)이라 같은 검색이면 같은 글이다. 길이를 고정하려고 해시로 줄인다. */
    private String digest(ListKey key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(key).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record ListKey(ProductListFilter filter, ProductSort sort, int page, int size) {
    }
}
