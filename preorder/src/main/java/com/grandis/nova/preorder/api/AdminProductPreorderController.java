package com.grandis.nova.preorder.api;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.campaign.PreorderCampaignAdminService;
import com.grandis.nova.preorder.campaign.ShipmentBatch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;

/**
 * 사전예약 상품의 모집 일정 · 배송 차수 관리. 권한은 보안 설정이 경로로 막는다(ADMIN).
 * 상품 · 옵션은 catalog 가, 회차와 차수는 preorder 가 소유한다.
 */
@Tag(name = "관리자 · 모집 일정")
@RestController
@RequestMapping("/api/v1/admin/products/{productId}")
public class AdminProductPreorderController {

    private final PreorderCampaignAdminService campaignService;
    private final Clock clock;

    public AdminProductPreorderController(PreorderCampaignAdminService campaignService, Clock clock) {
        this.campaignService = campaignService;
        this.clock = clock;
    }

    @Operation(summary = "모집 일정 조회")
    @GetMapping("/preorder-campaign")
    public ApiResponse<PreorderCampaignResponse> getCampaign(@PathVariable Long productId) {
        return ApiResponse.ok(PreorderCampaignResponse.from(campaignService.findCampaign(productId), clock.instant()));
    }

    /** 없으면 만들고 있으면 바꾼다. 오픈 뒤에는 409. */
    @Operation(summary = "모집 일정 생성 · 변경(오픈 뒤 409)")
    @PutMapping("/preorder-campaign")
    public ApiResponse<PreorderCampaignResponse> putCampaign(@PathVariable Long productId,
                                                             @Valid @RequestBody PreorderCampaignRequest request) {
        return ApiResponse.ok(PreorderCampaignResponse.from(
                campaignService.upsertCampaign(productId, request.opensAt(), request.closesAt()), clock.instant()));
    }

    @Operation(summary = "배송 차수 조회")
    @GetMapping("/shipment-batches")
    public ApiResponse<Items<ShipmentBatchResponse>> getBatches(@PathVariable Long productId) {
        return ApiResponse.ok(new Items<>(toResponses(campaignService.findBatches(productId))));
    }

    /** 오픈 전 전체 교체. 오픈 뒤에는 409, 구간 규칙 위반이면 400. */
    @Operation(summary = "배송 차수 전체 교체(오픈 뒤 409)")
    @PutMapping("/shipment-batches")
    public ApiResponse<Items<ShipmentBatchResponse>> putBatches(@PathVariable Long productId,
                                                                @Valid @RequestBody ShipmentBatchesRequest request) {
        return ApiResponse.ok(new Items<>(
                toResponses(campaignService.replaceBatches(productId, request.toPlan()))));
    }

    private static List<ShipmentBatchResponse> toResponses(List<ShipmentBatch> batches) {
        return batches.stream().map(ShipmentBatchResponse::from).toList();
    }
}
