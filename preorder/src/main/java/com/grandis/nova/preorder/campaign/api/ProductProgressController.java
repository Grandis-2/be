package com.grandis.nova.preorder.campaign.api;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.campaign.application.CampaignProgressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 상품의 접수 현황 공개 조회. "지금 신청하면 몇 차 출고" 안내에 쓴다. 로그인 없이 볼 수 있다. */
@Tag(name = "배송 차수")
@RestController
@RequestMapping("/api/v1/preorders/products/{productId}/progress")
class ProductProgressController {

    private final CampaignProgressService progressService;

    ProductProgressController(CampaignProgressService progressService) {
        this.progressService = progressService;
    }

    @Operation(summary = "접수 현황 공개 조회(로그인 없이) — 접수 상태 · 지금 신청하면 배정될 배송 차수",
            description = "잠깐 캐시한 값이다. 회차 · 배송 차수가 없거나 공개 전인 상품은 404")
    @SecurityRequirements
    @GetMapping
    public ApiResponse<CampaignProgressResponse> progress(@PathVariable UUID productId) {
        return ApiResponse.ok(CampaignProgressResponse.from(progressService.find(productId)));
    }
}
