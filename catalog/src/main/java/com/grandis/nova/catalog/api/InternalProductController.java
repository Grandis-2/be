package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.query.ProductOptionsQueryService;
import com.grandis.nova.catalog.query.ProductOptionsView;
import com.grandis.nova.common.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 내부 조회. preorder 의 CatalogClient 가 회차 설정과 접수 때 부른다. 계약: contracts/preorder-internal.md.
 *
 * 호출자의 JWT(X-Session-Token)를 검증해 USER · ADMIN 둘 다 허용한다 — 접수(회원)와 관리자 등록 흐름이 같은 API 를 쓴다.
 * 토큰 검증 필터는 common:security 채택 때 붙는다. 그 전까지 운영에서는 정상 토큰도 401 이다(경로 규칙만 있고 인증을 채우는 것이 없다).
 * /internal/** 은 공개 라우팅에서 빠져야 한다. USER 토큰으로 비공개 상품까지 돌려주므로 그 차단은 운영 필수 조건이다.
 */
@RestController
@RequestMapping("/internal/products")
public class InternalProductController {

    private final ProductOptionsQueryService queryService;

    public InternalProductController(ProductOptionsQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/{productId}/options")
    public ApiResponse<ProductOptionsView> productOptions(@PathVariable Long productId) {
        return ApiResponse.ok(queryService.findProductOptions(productId));
    }
}
