package com.grandis.nova.payment.refund.api;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.web.ApiError;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.payment.refund.RefundAdmin;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 멈춘 환불의 조회 · 관리자 조치(내부 API). payment 는 ALB 에 없어 관리자 화면은 order 가 받아 관리자 토큰을 그대로 실어 부른다. 역할(ADMIN)은
 * 보안 설정이 경로로 막고, 누가 했는지는 그 토큰의 주체다. 조치는 결제사 조회를 거쳐 최대 약 1분 걸린다(조회 읽기 기한).
 */
@RestController
class InternalRefundAdminController {

    static final int MAX_STALE_HOURS = 720;

    private final RefundAdmin refundAdmin;

    InternalRefundAdminController(RefundAdmin refundAdmin) {
        this.refundAdmin = refundAdmin;
    }

    /**
     * orderIds 가 없으면 결제 쪽에서 멈췄을 수 있는 주문(만든 순, 최대 {@link RefundAdmin#MAX_ISSUES}건), 있으면 그 주문들의 판정이다.
     * 진행 중인 환불은 staleHours 보다 오래 열렸을 때 IN_PROGRESS_LONG 이다.
     */
    @GetMapping("/internal/refund-issues")
    public ApiResponse<List<RefundIssueResponse>> issues(
            @RequestParam(required = false) List<UUID> orderIds,
            @RequestParam(defaultValue = "1") int staleHours) {
        if (staleHours < 1 || staleHours > MAX_STALE_HOURS) {
            throw invalid("staleHours", "1~%d 이어야 합니다.".formatted(MAX_STALE_HOURS));
        }
        Duration stale = Duration.ofHours(staleHours);
        var found = orderIds == null || orderIds.isEmpty()
                ? refundAdmin.issues(stale)
                : refundAdmin.issuesOf(limited(orderIds), stale);
        return ApiResponse.ok(found.stream().map(RefundIssueResponse::from).toList());
    }

    /** 다시 보낸다. 새 환불 거래를 열고 시작만 알린다 — 결과는 평소처럼 주문에 이벤트로 간다. */
    @PostMapping("/internal/refunds/{orderId}/retry")
    public ResponseEntity<ApiResponse<RefundAdmin.RefundAdminResult>> retry(@PathVariable UUID orderId,
                                                                         @Valid @RequestBody RefundAdminRequest request,
                                                                         Authentication admin) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(
                refundAdmin.retry(orderId, request.expectedTransactionId(), request.note(), admin.getName())));
    }

    /** 결제사에서 직접 환불한 것을 기록한다. 환불 완료가 평소처럼 주문에 이벤트로 간다. */
    @PostMapping("/internal/refunds/{orderId}/manual-complete")
    public ApiResponse<RefundAdmin.RefundAdminResult> manualComplete(@PathVariable UUID orderId,
                                                                     @Valid @RequestBody RefundAdminRequest request,
                                                                     Authentication admin) {
        return ApiResponse.ok(
                refundAdmin.manualComplete(orderId, request.expectedTransactionId(), request.note(), admin.getName()));
    }

    private static List<UUID> limited(List<UUID> orderIds) {
        if (orderIds.size() > RefundAdmin.MAX_ISSUES) {
            throw invalid("orderIds", "%d 개까지입니다.".formatted(RefundAdmin.MAX_ISSUES));
        }
        return orderIds;
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(CommonErrorCode.VALIDATION_FAILED,
                Map.of("violations", List.of(new ApiError.Violation(field, message))));
    }
}
