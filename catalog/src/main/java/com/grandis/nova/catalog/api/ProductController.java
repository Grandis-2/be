package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.detail.ProductDetailService;
import com.grandis.nova.catalog.detail.ProductDetailView;
import com.grandis.nova.catalog.listing.ProductListFilter;
import com.grandis.nova.catalog.listing.ProductListItem;
import com.grandis.nova.catalog.listing.ProductListingService;
import com.grandis.nova.catalog.web.Viewers;
import com.grandis.nova.catalog.product.SaleMode;
import com.grandis.nova.common.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 상품 목록 · 검색 · 상세 공개 조회. 로그인 없이 볼 수 있다.
 * color · storage 는 같은 이름을 반복해 여러 값을 준다(?color=블랙&color=화이트). 정렬은 productId 내림차순 고정.
 * 상세는 비공개 · 미완료 상품이면 회원에게 404, 관리자에게는 미리보기다.
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductListingService listingService;
    private final ProductDetailService detailService;

    public ProductController(ProductListingService listingService, ProductDetailService detailService) {
        this.listingService = listingService;
        this.detailService = detailService;
    }

    @GetMapping("/{productId}")
    public ApiResponse<ProductDetailView> product(@PathVariable Long productId) {
        return ApiResponse.ok(detailService.findProduct(productId, Viewers.isAdmin()));
    }

    @GetMapping("/{productId}/variants/{variantId}")
    public ApiResponse<ProductDetailView.Variant> variant(@PathVariable Long productId, @PathVariable Long variantId) {
        return ApiResponse.ok(detailService.findVariant(productId, variantId, Viewers.isAdmin()));
    }

    @GetMapping
    public ApiResponse<ProductPageResponse<ProductListItem>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) SaleMode saleMode,
            @RequestParam(required = false) List<String> color,
            @RequestParam(required = false) List<String> storage,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        ProductListFilter filter = new ProductListFilter(q, categoryId, saleMode, color, storage);
        return ApiResponse.ok(ProductPageResponse.from(
                listingService.list(filter, PageSizes.requirePage(page), PageSizes.require(size))));
    }
}
