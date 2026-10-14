package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.cache.CatalogReadCache;
import com.grandis.nova.catalog.detail.ProductDetailView;
import com.grandis.nova.catalog.listing.ProductListFilter;
import com.grandis.nova.catalog.listing.ProductListItem;
import com.grandis.nova.catalog.listing.ProductSort;
import com.grandis.nova.catalog.product.Brand;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * 상품 목록 · 검색 · 상세 공개 조회. 로그인 없이 볼 수 있다.
 * color · storage 는 같은 이름을 반복해 여러 값을 준다(?color=블랙&color=화이트). 정렬은 sort 셋 중 하나 — 최신순(기본) · 낮은 · 높은 가격순({@link ProductSort}).
 * 상세는 비공개 · 미완료 상품이면 누구에게나 404 — 관리자 미리보기는 없다(관리자는 /api/v1/admin/products/{id}).
 */
@Tag(name = "상품")
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final CatalogReadCache cache;
    private final Clock clock;

    public ProductController(CatalogReadCache cache, Clock clock) {
        this.cache = cache;
        this.clock = clock;
    }

    @Operation(summary = "상품 상세(로그인 없이)")
    @SecurityRequirements
    @GetMapping("/{productId}")
    public ResponseEntity<ApiResponse<ProductDetailView>> product(@PathVariable UUID productId) {
        return PublicCache.ok(cache.product(productId), CatalogReadCache.PRODUCT_TTL, PublicCache.PRODUCT, clock);
    }

    @Operation(summary = "옵션(조합) 상세(로그인 없이)")
    @SecurityRequirements
    @GetMapping("/{productId}/variants/{variantId}")
    public ResponseEntity<ApiResponse<ProductDetailView.Variant>> variant(@PathVariable UUID productId, @PathVariable UUID variantId) {
        return PublicCache.ok(cache.variant(productId, variantId), CatalogReadCache.PRODUCT_TTL, PublicCache.PRODUCT, clock);
    }

    @Operation(summary = "상품 목록 · 검색(로그인 없이)", description = "sort: NEWEST(최신순, 기본) · PRICE_ASC(낮은 가격순) · PRICE_DESC(높은 가격순). 가격은 판매 중인 옵션의 최저 할인가(minPrice)이고 판매 중인 옵션이 없는 상품은 가격순에서 맨 뒤. "
            + "q 는 상품명 · 태그 · 모델명 · 브랜드 코드 부분 일치, brand · color · storage 는 이름을 반복해 여러 값(OR — brand=APPLE&brand=LG)")
    @SecurityRequirements
    @GetMapping
    public ResponseEntity<ApiResponse<ProductPageResponse<ProductListItem>>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) SaleMode saleMode,
            @RequestParam(required = false) List<Brand> brand,
            @RequestParam(required = false) List<String> color,
            @RequestParam(required = false) List<String> storage,
            @RequestParam(defaultValue = "NEWEST") ProductSort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        ProductListFilter filter = new ProductListFilter(q, categoryId, saleMode, brand, color, storage);
        return PublicCache.ok(cache.products(filter, sort, PageSizes.requirePage(page), PageSizes.require(size)).map(ProductPageResponse::from),
                CatalogReadCache.PRODUCT_TTL, PublicCache.PRODUCT, clock);
    }
}
