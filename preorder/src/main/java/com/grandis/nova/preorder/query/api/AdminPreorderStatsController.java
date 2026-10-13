package com.grandis.nova.preorder.query.api;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.query.application.PreorderStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 관리자 운영 현황. 권한은 보안 설정이 경로로 막는다(ADMIN). */
@Tag(name = "관리자 · 예약")
@RestController
@RequestMapping("/api/v1/admin/preorders/stats")
class AdminPreorderStatsController {

    private final PreorderStatsService statsService;

    AdminPreorderStatsController(PreorderStatsService statsService) {
        this.statsService = statsService;
    }

    /** 전체 또는 상품 하나의 현황. 잠깐 캐시한 값이라 센 시각(countedAt)을 함께 준다. */
    @Operation(summary = "운영 현황 — 예약 상태별 수 · 끝나지 않은 동기화 작업 · DLQ 대기 · 결제 기한 초과",
            description = "productId 를 주면 그 상품만 센다. 사전예약 상품이 아니면 404")
    @GetMapping
    public ApiResponse<PreorderStatsResponse> stats(@RequestParam(required = false) UUID productId) {
        return ApiResponse.ok(PreorderStatsResponse.from(statsService.find(productId)));
    }
}
