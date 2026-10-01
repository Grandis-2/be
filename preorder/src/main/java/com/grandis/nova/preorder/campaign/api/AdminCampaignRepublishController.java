package com.grandis.nova.preorder.campaign.api;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.campaign.CampaignRepublisher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 회차 일정 전체 재발행(수동). 권한은 보안 설정이 경로로 막는다(ADMIN). */
@Tag(name = "관리자 · 모집 일정")
@RestController
@RequestMapping("/api/v1/admin/preorders/campaigns")
class AdminCampaignRepublishController {

    private final CampaignRepublisher republisher;

    AdminCampaignRepublishController(CampaignRepublisher republisher) {
        this.republisher = republisher;
    }

    /** 이벤트는 아웃박스에 적힌 뒤 발행기가 보낸다. 여러 번 불러도 된다. */
    @Operation(summary = "회차 일정 전체 재발행 — 마감 전 회차마다 지금 일정을 대기열로 다시 보냄")
    @PostMapping("/republish")
    public ResponseEntity<ApiResponse<CampaignRepublishResponse>> republish() {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(new CampaignRepublishResponse(republisher.republishAll())));
    }
}
