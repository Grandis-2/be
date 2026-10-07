package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.query.ProductOptionsQueryService;
import com.grandis.nova.catalog.query.ProductOptionsView;
import com.grandis.nova.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 내부 조회. preorder 의 CatalogClient 가 회차 설정과 접수 때 부른다. 계약: contracts/preorder-internal.md.
 *
 * 호출자의 JWT(`Authorization: Bearer`)를 common:security 필터가 검증해 USER · ADMIN 둘 다 허용한다 — 접수(회원)와 관리자 등록 흐름이 같은 API 를 쓴다.
 * preorder 는 보안 맥락에 있는 사용자 토큰을 CatalogClient 의 Authorization 헤더로 실어 보낸다. 보안 맥락이 없으면(스케줄러 · 큐 소비) 헤더 없이 오므로 여기서 401 이다.
 * /internal/** 은 공개 라우팅에서 빠져야 한다. USER 토큰으로 비공개 상품까지 돌려주므로 그 차단은 운영 필수 조건이다.
 */
@Tag(name = "서비스 간 상품 조회")
@RestController
@RequestMapping("/internal/products")
public class InternalProductController {

    private final ProductOptionsQueryService queryService;

    public InternalProductController(ProductOptionsQueryService queryService) {
        this.queryService = queryService;
    }

    @Operation(summary = "상품 옵션 · 가격 조회(preorder 가 부른다)")
    @GetMapping("/{productId}/options")
    public ApiResponse<ProductOptionsView> productOptions(@PathVariable Long productId) {
        return ApiResponse.ok(queryService.findProductOptions(productId));
    }
}
