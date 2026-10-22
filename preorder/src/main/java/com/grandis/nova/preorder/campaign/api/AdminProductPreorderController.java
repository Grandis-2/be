package com.grandis.nova.preorder.campaign.api;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.campaign.ShipmentBatchResponse;
import com.grandis.nova.preorder.campaign.application.PreorderCampaignAdminService;
import com.grandis.nova.preorder.campaign.domain.PreorderCampaign;
import com.grandis.nova.preorder.campaign.domain.ShipmentBatch;
import com.grandis.nova.preorder.web.Items;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * 사전예약 상품의 모집 일정 · 배송 차수 관리. 권한은 보안 설정이 경로로 막는다(ADMIN).
 * 상품 · 옵션은 catalog 가, 회차와 차수는 preorder 가 소유한다.
 */
@Tag(name = "관리자 · 모집 일정")
@RestController
@RequestMapping("/api/v1/admin/preorders/products/{productId}")
class AdminProductPreorderController {

    private final PreorderCampaignAdminService campaignService;
    private final Clock clock;

    AdminProductPreorderController(PreorderCampaignAdminService campaignService, Clock clock) {
        this.campaignService = campaignService;
        this.clock = clock;
    }

    @Operation(summary = "모집 일정 조회")
    @GetMapping("/campaign")
    public ApiResponse<PreorderCampaignResponse> getCampaign(@PathVariable UUID productId) {
        return ApiResponse.ok(PreorderCampaignResponse.from(campaignService.findCampaign(productId),
                campaignService.countOpenAlertSubscribers(productId), clock.instant()));
    }

    /** 없으면 만들고 있으면 바꾼다. 오픈 뒤에는 409. */
    @Operation(summary = "모집 일정 생성 · 변경(오픈 뒤 409)")
    @PutMapping("/campaign")
    public ApiResponse<PreorderCampaignResponse> putCampaign(@PathVariable UUID productId,
                                                             @Valid @RequestBody PreorderCampaignRequest request) {
        return response(campaignService.upsertCampaign(productId, request.opensAt(), request.closesAt(),
                request.expectedVisitors()));
    }

    /** 접수 중인 회차를 지금 마감한다. 새 접수만 막고 기존 예약은 그대로다. 다시 열기로 되돌린다. */
    @Operation(summary = "접수 조기 마감(오픈 전 409)",
            description = "새 접수만 막고 기존 예약은 그대로 둔다. 이미 마감이면 현재 일정을 그대로 준다. 다시 열기로 되돌린다")
    @PostMapping("/campaign/close")
    public ApiResponse<PreorderCampaignResponse> closeCampaign(@PathVariable UUID productId, Authentication admin) {
        return response(campaignService.closeCampaign(productId, admin.getName()));
    }

    /** 마감된 회차를 새 마감 시각까지 다시 연다. 발급한 순번은 이어진다. */
    @Operation(summary = "마감한 접수 다시 열기",
            description = "오픈 전 · 아직 접수 중 · 오픈 전에 판매 중지로 닫힘 · 지금 판매 중인 사전예약 상품이 아니면 409, "
                    + "마감 시각이 지금 이전이면 400. "
                    + "새 접수는 순번을 이어 받는다")
    @PostMapping("/campaign/reopen")
    public ApiResponse<PreorderCampaignResponse> reopenCampaign(@PathVariable UUID productId,
                                                                @Valid @RequestBody ReopenCampaignRequest request,
                                                                Authentication admin) {
        return response(campaignService.reopenCampaign(productId, request.closesAt(), admin.getName()));
    }

    @Operation(summary = "배송 차수 조회")
    @GetMapping("/shipment-batches")
    public ApiResponse<Items<ShipmentBatchResponse>> getBatches(@PathVariable UUID productId) {
        return ApiResponse.ok(new Items<>(toResponses(campaignService.findBatches(productId))));
    }

    /** 오픈 전 전체 교체. 오픈 뒤에는 409, 구간 규칙 위반이면 400. */
    @Operation(summary = "배송 차수 전체 교체(오픈 뒤 409)")
    @PutMapping("/shipment-batches")
    public ApiResponse<Items<ShipmentBatchResponse>> putBatches(@PathVariable UUID productId,
                                                                @Valid @RequestBody ShipmentBatchesRequest request) {
        return ApiResponse.ok(new Items<>(
                toResponses(campaignService.replaceBatches(productId, request.toPlan()))));
    }

    /** 바꾼 뒤의 응답에는 신청 수를 세지 않는다 — 세다 실패하면 이미 반영된 변경이 오류로 보인다. */
    private ApiResponse<PreorderCampaignResponse> response(PreorderCampaign campaign) {
        return ApiResponse.ok(PreorderCampaignResponse.from(campaign, null, clock.instant()));
    }

    private List<ShipmentBatchResponse> toResponses(List<ShipmentBatch> batches) {
        return batches.stream().map(ShipmentBatchResponse::from).toList();
    }
}
