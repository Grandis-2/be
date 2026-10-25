package com.grandis.nova.order.order.api;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.web.ApiError;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.client.payment.PaymentRefundAdminRequest;
import com.grandis.nova.order.client.payment.PaymentRefundAdminResult;
import com.grandis.nova.order.order.cancel.RefundIssues;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 멈춘 환불의 관리자 목록 · 조치. 조치는 payment 가 결제사 조회로 확인한 뒤 한다 — payment 는 ALB 에 없어 order 가 받아 payment 내부 API 로
 * 넘긴다. 권한은 보안 설정이 경로로 막고(ADMIN), 관리자 토큰을 그대로 실어 payment 가 조치한 사람을 남긴다.
 */
@RestController
@Tag(name = "관리자 · 환불 멈춤")
public class AdminRefundIssueController {

    static final String ISSUES_PATH = "/api/v1/admin/orders/refund-issues";
    static final int MAX_STALE_HOURS = 720;

    private final RefundIssues refundIssues;

    public AdminRefundIssueController(RefundIssues refundIssues) {
        this.refundIssues = refundIssues;
    }

    @GetMapping(ISSUES_PATH)
    @Operation(summary = "멈춘 환불 목록", description = "취소 중인데 환불이 확정 실패 · 오래 멈춘 주문, 자동 환불을 요청했는데 끝나지 않은 주문, "
            + "payment 에서 환불이 확정 실패 · 에스컬레이션된 주문. 최대 500건. staleHours(1~720, 기본 1) 보다 오래된 진행 중만 멈춘 것으로 본다")
    public ApiResponse<List<AdminRefundIssueResponse>> list(@RequestParam(defaultValue = "1") int staleHours,
                                                            HttpServletRequest httpRequest) {
        if (staleHours < 1 || staleHours > MAX_STALE_HOURS) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED, Map.of("violations",
                    List.of(new ApiError.Violation("staleHours", "1~%d 이어야 합니다.".formatted(MAX_STALE_HOURS)))));
        }
        return ApiResponse.ok(refundIssues.list(staleHours, token(httpRequest)).stream()
                .map(AdminRefundIssueResponse::from).toList());
    }

    @PostMapping("/api/v1/admin/orders/{orderId}/refund/retry")
    @Operation(summary = "환불 다시 보내기", description = "확정 실패 · 결과를 끝내 몰라 멈춘 환불만. 결제사가 아직 한 푼도 취소하지 않았을 때만 "
            + "새 환불 거래를 연다(시작만 알린다 — 결과는 평소처럼 반영된다). 최대 약 1분 걸린다. 응답을 받지 못해 503 이어도 이미 열었을 수 있다 — "
            + "목록으로 확인한다")
    public ResponseEntity<ApiResponse<PaymentRefundAdminResult>> retry(@PathVariable UUID orderId,
                                                                       @Valid @RequestBody PaymentRefundAdminRequest request,
                                                                       HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(refundIssues.retry(orderId, request, token(httpRequest))));
    }

    @PostMapping("/api/v1/admin/orders/{orderId}/refund/manual-complete")
    @Operation(summary = "직접 환불 완료 기록", description = "결제사 상점 관리자에서 직접 환불한 뒤. 결제사 조회로 전액 취소가 확인될 때만 환불 완료로 "
            + "기록하고 주문이 평소처럼 끝난다. 최대 약 1분 걸린다")
    public ApiResponse<PaymentRefundAdminResult> manualComplete(@PathVariable UUID orderId,
                                                                @Valid @RequestBody PaymentRefundAdminRequest request,
                                                                HttpServletRequest httpRequest) {
        return ApiResponse.ok(refundIssues.manualComplete(orderId, request, token(httpRequest)));
    }

    private static String token(HttpServletRequest httpRequest) {
        return BearerTokens.extract(httpRequest).orElse(null);
    }
}
