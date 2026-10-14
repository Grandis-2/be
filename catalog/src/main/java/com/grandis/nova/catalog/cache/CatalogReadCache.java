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
import java.util.HexFormat;
import java.util.List;
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

    static final Duration PRODUCT_TTL = Duration.ofSeconds(5);
    static final Duration REVIEW_TTL = Duration.ofSeconds(60);
    static final Duration CATEGORY_TTL = Duration.ofMinutes(10);

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

    public ProductDetailView product(UUID productId) {
        return cache.get(detailSpec, PRODUCTS, productId.toString(), () -> details.findProduct(productId)).at(clock.instant());
    }

    /** 옵션 상세 — 상품 상세(같은 캐시)에서 꺼낸다. 그 상품의 옵션이 아니면 404(상세 서비스와 같은 판정). */
    public ProductDetailView.Variant variant(UUID productId, UUID variantId) {
        return product(productId).variants().stream()
                .filter(variant -> variant.variantId().equals(variantId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    }

    public OffsetPage<ProductListItem> products(ProductListFilter filter, ProductSort sort, int page, int size) {
        OffsetPage<ProductListItem> found = cache.get(listSpec, PRODUCTS, digest(new ListKey(filter, sort, page, size)),
                () -> listings.list(filter, sort, page, size));
        return found.map(item -> item.at(clock.instant()));
    }

    public List<CategoryNode> categoryTree() {
        return cache.get(categorySpec, CATEGORIES, "tree", categories::tree);
    }

    public OffsetPage<ReviewView> productReviews(UUID productId, int page, int size) {
        return cache.get(reviewSpec, REVIEWS, "product:" + productId + ":" + page + ":" + size,
                () -> reviews.productReviews(productId, page, size));
    }

    public OffsetPage<ReviewView> visibleReviews(UUID categoryId, int page, int size) {
        return cache.get(reviewSpec, REVIEWS, "visible:" + categoryId + ":" + page + ":" + size,
                () -> reviews.visibleReviews(categoryId, page, size));
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
