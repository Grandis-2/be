package com.grandis.nova.catalog.api;

import com.grandis.nova.catalog.promotion.PromotionQueryService;
import com.grandis.nova.catalog.promotion.PromotionViews;
import com.grandis.nova.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.UUID;

/** 고객 사전예약 페이지의 홍보(로그인 없이). 오늘(한국 날짜)이 홍보 기간 안인 홍보만. */
@Tag(name = "홍보")
@RestController
@RequestMapping("/api/v1/promotions")
public class PromotionController {

    private final PromotionQueryService promotions;
    private final Clock clock;

    public PromotionController(PromotionQueryService promotions, Clock clock) {
        this.promotions = promotions;
        this.clock = clock;
    }

    @Operation(summary = "홍보 목록(로그인 없이)", description = "오늘 보이는 홍보. opensAt · closesAt 은 묶인 사전예약 상품 중 가장 이른 오픈 · 가장 늦은 마감")
    @SecurityRequirements
    @GetMapping
    public ResponseEntity<ApiResponse<Items<PromotionViews.Summary>>> list() {
        return PublicCache.ok(promotions.shown().map(Items::new), PromotionQueryService.BODY_TTL, PublicCache.PRODUCT, clock);
    }

    @Operation(summary = "홍보 상세(로그인 없이)", description = "홍보 기간 밖이면 404. products 는 상품 목록과 같은 칸 — 공개 · 판매 중 · 판매 준비가 끝난 상품만(판매 중지 · 회차 취소 제외), 마감 · 일반 전환 뒤에도 싣는다. preorder 가 답하지 않으면 사전예약 카드는 빼지 않고 opensAt · closesAt · preorderStatus 만 null 이다")
    @SecurityRequirements
    @GetMapping("/{promotionId}")
    public ResponseEntity<ApiResponse<PromotionViews.Detail>> detail(@PathVariable UUID promotionId) {
        return PublicCache.ok(promotions.detail(promotionId), PromotionQueryService.BODY_TTL, PublicCache.PRODUCT, clock);
    }
}
