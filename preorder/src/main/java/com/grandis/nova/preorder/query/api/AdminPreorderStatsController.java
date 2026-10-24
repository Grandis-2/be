package com.grandis.nova.preorder.query.api;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.campaign.CampaignPhase;
import com.grandis.nova.preorder.query.application.PreorderStatsService;
import com.grandis.nova.preorder.query.application.StatsHistory;
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
    @Operation(summary = "운영 현황 — 예약 상태별 수 · 끝나지 않은 동기화 작업 · DLQ 대기 · 결제 기한 초과 · 속도",
            description = "productId 를 주면 그 상품만 센다. 사전예약 상품이 아니면 404. rates 는 최근 1분 기록으로 잰다")
    @GetMapping
    public ApiResponse<PreorderStatsResponse> stats(@RequestParam(required = false) UUID productId) {
        return ApiResponse.ok(PreorderStatsResponse.from(statsService.find(productId), statsService.rates(productId)));
    }

    /** 서버가 10초마다 쌓은 기록. 화면이 직접 쌓지 않아 새로 고쳐도 지난 값을 본다. 상품은 접수 중일 때만 쌓인다. */
    @Operation(summary = "예약 수 지난 기록 — 외부 등록 대기 · 등록 · 결제 완료",
            description = "minutes 는 기본 30, 1~30. 사전예약 상품이 아니면 404, 기록을 읽을 수 없으면 503")
    @GetMapping("/history")
    public ApiResponse<StatsHistory> history(@RequestParam(required = false) UUID productId,
                                             @RequestParam(required = false) Integer minutes) {
        return ApiResponse.ok(statsService.history(productId, minutes));
    }

    /** 상품마다 따로 부르지 않게 한 페이지의 상품들을 한 번에 센다. 대기열이 아니라 회차 접수 기간으로 골라 오래된 마감 상품도 나온다. */
    @Operation(summary = "상품별 운영 현황 — 접수 중(마감 임박순) · 마감(최근 마감순) 회차를 커서 페이지로",
            description = "항목은 /stats 와 같은 칸에 opensAt · closesAt 이 붙는다. 판매 중지 · 비공개 회차도 든다. "
                    + "마감이 같으면 상품 id 순(마감은 역순). size 는 기본 20, 1~100")
    @GetMapping("/products")
    public ApiResponse<ProductStatsPageResponse> products(@RequestParam CampaignPhase phase,
                                                          @RequestParam(required = false) String cursor,
                                                          @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(ProductStatsPageResponse.from(statsService.findProducts(phase, cursor, size)));
    }
}
