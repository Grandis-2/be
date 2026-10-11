package com.grandis.nova.preorder.integration.catalog;

import com.grandis.nova.common.web.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.UUID;

/**
 * catalog 내부 API. 상품과 옵션의 읽기는 catalog 에게 묻는다 — catalog 테이블을 직접 읽지 않는다.
 * 계약: contracts/preorder-internal.md 1.3.
 *
 * 접수마다 부르지 않는다. {@link CatalogReader} 가 상품 단위로 캐시한다.
 */
@HttpExchange("/internal/products")
public interface CatalogClient {

    /** 없는 상품이면 404 — 호출 쪽에서 빈 결과로 바꾼다. */
    @GetExchange("/{productId}/options")
    ApiResponse<ProductCatalog> getProduct(@PathVariable UUID productId);
}
