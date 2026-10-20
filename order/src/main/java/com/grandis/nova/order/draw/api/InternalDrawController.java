package com.grandis.nova.order.draw.api;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.draw.DrawQueryService;
import com.grandis.nova.order.web.CurrentViewer;
import com.grandis.nova.order.web.Viewer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 서비스 간 내부 API — 드로우 증정품. catalog 가 관리자의 상품 삭제 때 그 관리자의 토큰을 그대로 싣고(Authorization: Bearer) 부른다.
 * 관리자(ADMIN)만 받는다 — 회원 토큰은 403. 계약: contracts/order-internal.md.
 */
@RestController
@RequestMapping("/internal/draws")
public class InternalDrawController {

    private final DrawQueryService queries;

    public InternalDrawController(DrawQueryService queries) {
        this.queries = queries;
    }

    /** 그 상품이 아직 반영되지 않은 회차의 증정품인가. 회차가 없는 상품 · 없는 상품도 200(false)이다 — 상품의 유무는 catalog 가 안다. */
    @GetMapping("/gift-products/{productId}")
    public ApiResponse<DrawGiftResponse> gift(@CurrentViewer Viewer viewer, @PathVariable UUID productId) {
        if (!viewer.admin()) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return ApiResponse.ok(new DrawGiftResponse(productId, queries.giftInProgress(productId)));
    }

    /** @param drawInProgress 그 상품을 증정품으로 둔 회차 중 반영(settled_at) 전인 것이 있다 — 응모 전 · 응모 중 · 마감 뒤 추첨 · 반영 전 */
    public record DrawGiftResponse(UUID productId, boolean drawInProgress) {
    }
}
