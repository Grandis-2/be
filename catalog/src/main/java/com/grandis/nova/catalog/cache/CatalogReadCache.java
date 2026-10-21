package com.grandis.nova.catalog.cache;

import com.grandis.nova.catalog.category.CategoryNode;
import com.grandis.nova.catalog.category.CategoryTreeService;
import com.grandis.nova.catalog.detail.ProductDetailBody;
import com.grandis.nova.catalog.detail.ProductDetailService;
import com.grandis.nova.catalog.detail.ProductDetailView;
import com.grandis.nova.catalog.listing.ProductListFilter;
import com.grandis.nova.catalog.listing.ProductListItem;
import com.grandis.nova.catalog.listing.ProductListingService;
import com.grandis.nova.catalog.listing.ProductSort;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.catalog.review.ReviewService;
import com.grandis.nova.catalog.review.ReviewView;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.redis.cache.ReadCache;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 회원 공개 조회(로그인 없이 보는 상품 목록 · 상세 · 옵션 상세 · 카테고리 · 리뷰)의 읽기 캐시. 관리자 · 내부 조회(장바구니 · 접수 가격)는 거치지 않는다.
 *
 * <ul>
 *   <li><b>catalog 가 바꾸는 것</b>(등록 · 수정 · 공개 전환 · 판매 상태 · 사진 · 옵션 · 리뷰)은 커밋 뒤 세대를 올려 다음 조회부터 새 값이다
 *       ({@link CatalogCacheInvalidation}). 상품 목록은 고친 인스턴스만 바로, 다른 인스턴스는 {@link #PRODUCT_TTL} 안에 새 값이다 — 숨김 · 판매 중지 ·
 *       삭제한 상품도 다른 인스턴스의 목록에는 그동안 남는다(상세는 Redis 가 정상이면 모든 인스턴스가 바로 404 · 판매 중지).</li>
 *   <li><b>다른 서비스가 바꾸는 것</b>(판매 준비 완료 — order 재고 행 · preorder 회차 행, 회차 시각 · 조기 마감, 재고 수량)은 catalog 가 알 수 없어
 *       TTL 이 지나야 보인다 — 그래서 상품 목록 · 상세의 TTL 은 짧다. 주문 · 접수는 이 캐시를 거치지 않고 다시 확인한다.</li>
 *   <li><b>시각으로 정하는 칸</b>(사전예약 접수 단계)은 꺼낼 때 지금 시각으로 다시 계산한다.</li>
 *   <li><b>앞단 HTTP 캐시</b>는 돌려준 {@link ReadCache.Entry#loadedAt()} 로 남은 시간만큼만 허용한다(PublicCache) — 두 단계를 합쳐도 TTL 을 넘지
 *       않게.</li>
 * </ul>
 * 상품 목록 · 카테고리 트리는 인스턴스 메모리({@link CatalogLocalCaches}), 나머지는 Redis 다. Redis 가 실패하면 캐시 없이 DB 로 답한다({@link ReadCache}).
 * 메모리 캐시의 비우기는 그 인스턴스에만 닿는다 — 다른 인스턴스는 TTL 이 지나야 새 값이다(그래서 TTL 이 짧거나 고치는 쪽이 없는 조회만 둔다).
 */
@Component
public class CatalogReadCache {

    /** 상품 · 옵션이 바뀌면 올리는 세대. 상품 목록 · 상세 · 옵션 상세가 쓴다. */
    static final String PRODUCTS = "products";
    /** 리뷰가 바뀌거나 상품이 바뀌면(공개 여부가 리뷰 노출을 정한다) 올리는 세대. */
    static final String REVIEWS = "reviews";

    /**
     * 상품 목록(노출이 쿼리 안에서 정해지고, 품절 표시는 담을 때 order 에 물어 같이 담는다 — 실시간 칸을 따로 읽지 않는다). 사전예약이 목록에
     * 나오고 빠지는 순간(오픈 · 마감)도 쿼리 시각 기준이라 이만큼 늦을 수 있다 — 접수 단계 칸은 꺼낼 때 다시 계산한다. order 가 답하지 않아 품절
     * 표시를 뺀 목록도 이만큼 담긴다.
     */
    public static final Duration PRODUCT_TTL = Duration.ofSeconds(5);
    /** 상품 상세의 본문 — catalog 가 고치면 바로 비우므로 이 값은 놓친 비우기의 상한이다. 실시간 칸은 캐시하지 않는다. */
    public static final Duration DETAIL_TTL = Duration.ofMinutes(10);
    public static final Duration REVIEW_TTL = Duration.ofSeconds(60);
    public static final Duration CATEGORY_TTL = Duration.ofMinutes(10);
    /** 목록 · 리뷰는 앞 페이지(0~4)만 넣는다. */
    static final int CACHED_PAGES = 5;
    /** 화면이 쓰는 상품 목록 size(추천 4 · 랭킹 10 · 목록 · 검색 12 · 기본 20). 다른 size 는 넣지 않는다. */
    static final Set<Integer> CACHED_PRODUCT_SIZES = Set.of(4, 10, 12, 20);
    /** 화면이 쓰는 리뷰 size(목록 100 · 기본 20). */
    static final Set<Integer> CACHED_REVIEW_SIZES = Set.of(20, 100);

    private final ReadCache cache;
    private final CatalogLocalCaches local;
    private final Clock clock;
    private final ProductDetailService details;
    private final ProductListingService listings;
    private final CategoryTreeService categories;
    private final ReviewService reviews;

    private final ReadCache.Spec<ProductDetailBody> detailSpec;
    private final ReadCache.Spec<OffsetPage<ReviewView>> reviewSpec;

    public CatalogReadCache(ReadCache cache, CatalogLocalCaches local, Clock clock, ProductDetailService details, ProductListingService listings,
                            CategoryTreeService categories, ReviewService reviews) {
        this.cache = cache;
        this.local = local;
        this.clock = clock;
        this.details = details;
        this.listings = listings;
        this.categories = categories;
        this.reviews = reviews;
        this.detailSpec = cache.spec("product-detail-body", ProductDetailBody.class, DETAIL_TTL);
        this.reviewSpec = cache.spec("reviews", new TypeReference<OffsetPage<ReviewView>>() { }, REVIEW_TTL);
    }

    /**
     * 상품 상세. 본문(catalog 가 정하는 칸 — 일반 상품의 판매 준비 포함, 준비 전 일반 상품은 넣지 않는다)만 {@link #DETAIL_TTL} 동안 캐시하고, 실시간 칸(회차 · 재고)은 요청마다 채운다
     * (회차는 PublicCampaigns 를 거친다 — 켜 두면 그 캐시에서) — 가격 · 재고를
     * 상품 정보와 따로 다루는 커머스 플랫폼들의 방식(본문은 길게, 가격 · 재고는 실시간)을 따른다. 가격 · 할인율은 catalog 가 바꾸므로 본문에 두고 세대로 비운다.
     * 돌려주는 읽은 시각은 본문의 것이다 — 앞단 HTTP 캐시는 PublicCache 의 상한(5초)으로 짧게 묶는다. 회차 캐시를 켜 두면 회차는 그 캐시의 나이에
     * 이 상한만큼 더 늦을 수 있다(회차의 읽은 시각은 max-age 에 넣지 않는다).
     */
    public ReadCache.Entry<ProductDetailView> product(UUID productId) {
        // 판매 준비 전인 일반 상품의 본문은 넣지 않는다 — 준비 이벤트의 세대 올리기가 실패해도 "목록에는 있는데 상세는 404" 가 TTL 동안 남지 않게
        // (준비는 되돌아가지 않으므로 넣은 본문의 준비는 낡지 않는다). 준비 전 상품의 상세는 요청마다 DB 로 간다
        ReadCache.Entry<ProductDetailBody> body = cache.entry(detailSpec, PRODUCTS, productId.toString(), () -> details.findProductBody(productId),
                found -> found.view().saleMode() != SaleMode.IN_STOCK || found.stockReady());
        // 실시간 칸은 loader 밖, 요청마다 자기 스레드에서 채운다 — 같이 기다린 요청도 여기서 채우므로, 준비 전 본문의 판매 준비 다시 읽기
        // (ProductDetailService#withLiveParts)가 커밋 뒤 요청에 새 값을 준다. loader 안으로 옮기면 그 다시 읽기가 무력해진다
        return body.map(details::withLiveParts);
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
     * 상품 목록(인스턴스 메모리). 넣는 것은 입력이 닫힌 조회뿐이다 — 자유 문자열(검색어 q · 색상 · 용량)이 없고, 앞 {@link #CACHED_PAGES} 페이지이고,
     * size 가 화면이 쓰는 값({@link #CACHED_PRODUCT_SIZES})이고, 결과가 비지 않았다(없는 카테고리 id 는 빈 목록이라 넣지 않는다). 나머지는 늘 DB 다.
     * 입력을 아무렇게나 바꿔 키를 끝없이 만들어도 같은 쪽을 다시 쓰지 못한 채 상한까지 밀어내지 않게. 넣는 양 자체도 무게 상한이 묶는다(CatalogLocalCaches).
     */
    public ReadCache.Entry<OffsetPage<ProductListItem>> products(ProductListFilter filter, ProductSort sort, int page, int size) {
        boolean closedInput = filter.q() == null && filter.colors().isEmpty() && filter.storages().isEmpty()
                && page < CACHED_PAGES && CACHED_PRODUCT_SIZES.contains(size);
        if (!closedInput) {
            return cache.uncached(() -> listings.list(filter, sort, page, size));
        }
        ProductListFilter keyed = new ProductListFilter(null, filter.categoryId(), filter.saleMode(),
                filter.brands().stream().sorted().toList(), List.of(), List.of());   // OR 조건은 순서를 맞춰 같은 조건이 같은 키
        ReadCache.Entry<OffsetPage<ProductListItem>> entry = local.productList().entry(new ListKey(keyed, sort, page, size),
                () -> listings.list(filter, sort, page, size), found -> !found.items().isEmpty());
        Instant served = clock.instant();
        return new ReadCache.Entry<>(entry.value().map(item -> item.at(served)), entry.loadedAt());
    }

    /** 카테고리 트리(인스턴스 메모리). 고치는 API 가 없어 비우지 않는다 — 인스턴스마다 {@link #CATEGORY_TTL} 뒤 다시 읽는다. */
    public ReadCache.Entry<List<CategoryNode>> categoryTree() {
        return local.categoryTree().entry("tree", categories::tree);
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

    /** 목록 조건의 키. 조건은 정규화된 값(ProductListFilter)이라 같은 검색이면 같은 값이다. */
    record ListKey(ProductListFilter filter, ProductSort sort, int page, int size) {
    }
}
