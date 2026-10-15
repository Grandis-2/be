package com.grandis.nova.preorder.campaign.api;

import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.campaign.application.OpenAlertService;
import com.grandis.nova.preorder.web.Items;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 오픈 알림 신청(회원). 회차가 열리면 신청자에게 회원 알림이 간다. */
@Tag(name = "오픈 알림")
@RestController
@RequestMapping("/api/v1/preorders")
class OpenAlertController {

    private final OpenAlertService openAlertService;

    OpenAlertController(OpenAlertService openAlertService) {
        this.openAlertService = openAlertService;
    }

    @Operation(summary = "오픈 알림 신청 — 다시 해도 한 건",
            description = "오픈 전 · 공개 · 판매 중인 사전예약만. 회차가 없거나 비공개면 404 PRODUCT_NOT_FOUND, "
                    + "이미 열렸으면 409 PRODUCT_ALREADY_OPEN, 판매 중지면 409 PRODUCT_PAUSED")
    @PutMapping("/products/{productId}/open-alert")
    public ApiResponse<OpenAlertResponse> subscribe(@CurrentCustomerId UUID customerId, @PathVariable UUID productId) {
        openAlertService.subscribe(customerId, productId);
        return ApiResponse.ok(new OpenAlertResponse(productId, true));
    }

    @Operation(summary = "오픈 알림 해제 — 신청이 없어도 204")
    @DeleteMapping("/products/{productId}/open-alert")
    public ResponseEntity<Void> unsubscribe(@CurrentCustomerId UUID customerId, @PathVariable UUID productId) {
        openAlertService.unsubscribe(customerId, productId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "이 상품에 오픈 알림을 신청했는가", description = "상품 상세의 토글 초기값. 회차가 없어도 subscribed=false")
    @GetMapping("/products/{productId}/open-alert")
    public ApiResponse<OpenAlertResponse> subscribed(@CurrentCustomerId UUID customerId, @PathVariable UUID productId) {
        return ApiResponse.ok(new OpenAlertResponse(productId, openAlertService.isSubscribed(customerId, productId)));
    }

    @Operation(summary = "내 오픈 알림 신청 목록 — 오픈 전 공개 회차만, 오픈이 이른 순 100개까지")
    @GetMapping("/open-alerts")
    public ApiResponse<Items<OpenAlertItemResponse>> mine(@CurrentCustomerId UUID customerId) {
        return ApiResponse.ok(new Items<>(openAlertService.findMine(customerId).stream()
                .map(OpenAlertItemResponse::from).toList()));
    }
}
