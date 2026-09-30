package com.grandis.nova.preorder.cancel;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예약 취소 시작. 받은 액세스 토큰(Authorization: Bearer)은 order 사전 확인에 그대로 싣는다.
 * Idempotency-Key 는 받지 않는다 — 취소는 상태 조건으로 이미 멱등하다(계약: 선택 헤더).
 */
@RestController
class PreorderCancelController {

    private final PreorderCancelService cancelService;

    PreorderCancelController(PreorderCancelService cancelService) {
        this.cancelService = cancelService;
    }

    @Operation(summary = "예약 취소 시작 — order 에 취소 가능 여부를 먼저 확인", tags = "사전예약")
    @PostMapping("/api/v1/preorders/{preorderId}/cancel")
    public ResponseEntity<ApiResponse<CancelResult>> cancel(
            @CurrentCustomerId Long customerId,
            @PathVariable String preorderId,
            @Parameter(hidden = true) @RequestHeader(value = BearerTokens.HEADER, required = false)
            String authorization,
            @Valid @RequestBody(required = false) CancelRequest request) {
        String reason = request == null ? null : request.reason();
        return CancelResponses.accepted(cancelService.cancelByCustomer(customerId, preorderId, reason,
                accessToken(authorization)));
    }

    @Operation(summary = "관리자 예약 취소", tags = "관리자 · 예약")
    @PostMapping("/api/v1/admin/preorders/{preorderId}/cancel")
    public ResponseEntity<ApiResponse<CancelResult>> cancelByAdmin(
            @PathVariable String preorderId,
            @Parameter(hidden = true) @RequestHeader(value = BearerTokens.HEADER, required = false)
            String authorization,
            @Valid @RequestBody AdminCancelRequest request) {
        return CancelResponses.accepted(cancelService.cancelByAdmin(preorderId, request.reason(),
                accessToken(authorization)));
    }

    private String accessToken(String authorization) {
        return BearerTokens.parse(authorization).orElse(null);
    }
}
