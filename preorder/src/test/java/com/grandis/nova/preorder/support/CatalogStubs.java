package com.grandis.nova.preorder.support;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.integration.catalog.ProductCatalog;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/** catalog 내부 API 응답 대역. 옵션 sku 는 "SKU-{옵션 id}", 가격은 1,250,000원으로 고정한다. */
public final class CatalogStubs {

    public static final BigDecimal PRICE = new BigDecimal("1250000");

    private CatalogStubs() {
    }

    public static ApiResponse<ProductCatalog> product(Long productId, String saleMode, String status,
                                                      ProductCatalog.Option... options) {
        return ApiResponse.ok(new ProductCatalog(productId, "Nova 1", saleMode, status, List.of(options)));
    }

    /** 판매 중인 사전예약 상품. */
    public static ApiResponse<ProductCatalog> preorderProduct(Long productId, ProductCatalog.Option... options) {
        return product(productId, "PREORDER", "ACTIVE", options);
    }

    public static ProductCatalog.Option option(Long optionId, String status) {
        return new ProductCatalog.Option(optionId, "SKU-" + optionId, "블랙 / 256GB", PRICE, status);
    }

    public static ProductCatalog.Option activeOption(Long optionId) {
        return option(optionId, "ACTIVE");
    }

    /** catalog 대역이 그 상품을 사전예약 판매 중으로 답하게 한다. */
    public static void stubPreorderProduct(CatalogClient client, Long productId, ProductCatalog.Option... options) {
        given(client.getProduct(productId)).willReturn(preorderProduct(productId, options));
    }

    public static void stubProduct(CatalogClient client, Long productId, String saleMode, String status,
                                   ProductCatalog.Option... options) {
        given(client.getProduct(productId)).willReturn(product(productId, saleMode, status, options));
    }

    /** catalog 대역이 없는 상품으로 답하게 한다. */
    public static void stubNotFound(CatalogClient client, Long productId) {
        given(client.getProduct(productId))
                .willThrow(HttpClientErrorException.create(NOT_FOUND, "Not Found", null, null, null));
    }
}
