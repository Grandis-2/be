package com.grandis.nova.preorder.cancel.api;

import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.cancel.application.CancelJobResolveService;
import com.grandis.nova.preorder.cancel.application.CancelResult;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 포기한(DEAD_LETTER) 외부 취소 작업을 관리자가 끝낸다. 조회 · 재처리는 동기화 작업 API 가 맡는다. */
@RestController
class AdminCancelJobController {

    private final CancelJobResolveService resolveService;

    AdminCancelJobController(CancelJobResolveService resolveService) {
        this.resolveService = resolveService;
    }

    @Operation(summary = "포기한 외부 취소를 수동으로 끝내기 — 외부에서 취소를 확인한 뒤", tags = "관리자 · 동기화 작업")
    @PostMapping("/api/v1/admin/preorders/sync-jobs/{syncJobId}/resolve")
    public ApiResponse<CancelResult> resolve(@PathVariable UUID syncJobId,
                                             @Valid @RequestBody ResolveCancelJobRequest request,
                                             Authentication admin) {
        return ApiResponse.ok(resolveService.resolve(syncJobId, request.reason(), admin.getName()));
    }
}
