package com.grandis.nova.preorder.reconciliation.api;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.reconciliation.application.ReconciliationAdminService;
import com.grandis.nova.preorder.reconciliation.application.RunDiff;
import com.grandis.nova.preorder.web.PageSizes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 정합성 검사 — 실행 요청 · 결과 조회 · 불일치 조치. 검사는 batch 가 하고, 여기서는 결과 표를 읽고 요청 · 조치 칸만 쓴다.
 * 조치는 기존 업무 경로(등록 작업 재처리 · 관리자 예약 취소)로 하고, worker 가 처리한 결과는 다음 검사에서 그 건이 사라지는지로 본다.
 */
@Tag(name = "관리자 · 정합성 검사")
@RestController
@RequestMapping("/api/v1/admin/preorders/reconciliation")
class AdminReconciliationController {

    private final ReconciliationAdminService reconciliationAdminService;

    AdminReconciliationController(ReconciliationAdminService reconciliationAdminService) {
        this.reconciliationAdminService = reconciliationAdminService;
    }

    @Operation(summary = "검사 실행 요청 — batch 가 몇 초 안에 가져간다. 대기 · 실행 중이거나 오픈 후 1시간 안 회차가 있으면 409")
    @PostMapping("/runs")
    public ResponseEntity<ApiResponse<RunResponse>> requestRun(@RequestBody(required = false) RunRequest request,
                                                               Authentication admin) {
        return accepted(RunResponse.from(reconciliationAdminService.requestRun(
                request == null ? null : request.productId(), admin.getName())));
    }

    @Operation(summary = "실행 목록 — 최근 실행부터, 코드별 불일치 건수와 판정")
    @GetMapping("/runs")
    public ApiResponse<OffsetPage<RunResponse>> runs(@RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        return ApiResponse.ok(reconciliationAdminService.findRuns(PageSizes.requirePage(page), PageSizes.require(size))
                .map(RunResponse::from));
    }

    @Operation(summary = "실행의 불일치 목록 — 코드 · 대상 순, 코드로 거르기")
    @GetMapping("/runs/{runId}/findings")
    public ApiResponse<OffsetPage<FindingResponse>> findings(
            @PathVariable UUID runId,
            @RequestParam(required = false) String checkCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        return ApiResponse.ok(reconciliationAdminService.findFindings(runId, checkCode, PageSizes.requirePage(page),
                PageSizes.require(size)).map(FindingResponse::from));
    }

    @Operation(summary = "두 실행 비교 — 신규 · 잔여 · 해소. 둘 다 완료이고 범위가 같을 때만")
    @GetMapping("/runs/{runId}/diff")
    public ApiResponse<RunDiff> diff(@PathVariable UUID runId, @RequestParam UUID against) {
        return ApiResponse.ok(reconciliationAdminService.diff(runId, against));
    }

    @Operation(summary = "불일치 조치 — 등록 작업 재처리(be 반영 누락)")
    @PostMapping("/findings/{findingId}/reprocess")
    public ResponseEntity<ApiResponse<FindingResponse>> reprocess(@PathVariable UUID findingId, Authentication admin) {
        return accepted(FindingResponse.from(reconciliationAdminService.reprocess(findingId, admin.getName())));
    }

    @Operation(summary = "불일치 조치 — 관리자 예약 취소(Mock 에서 취소됨 · 등록 막힘). 결제 완료면 환불까지 이어진다")
    @PostMapping("/findings/{findingId}/cancel")
    public ResponseEntity<ApiResponse<FindingResponse>> cancel(@PathVariable UUID findingId,
                                                               @Valid @RequestBody FindingCancelRequest request,
                                                               Authentication admin) {
        return accepted(FindingResponse.from(
                reconciliationAdminService.cancel(findingId, request.reason(), admin.getName())));
    }

    @Operation(summary = "불일치 조치 — 키로 Mock 취소(be 예약은 그대로). be 가 취소 완료이거나 예약이 없을 때만. "
            + "결과를 바로 준다: SUCCEEDED · ALREADY_RESOLVED(이미 취소돼 보내지 않음) · FAILED(다시 누를 수 있다)")
    @PostMapping("/findings/{findingId}/external-cancel")
    public ApiResponse<FindingResponse> externalCancel(@PathVariable UUID findingId, Authentication admin) {
        return ApiResponse.ok(FindingResponse.from(reconciliationAdminService.externalCancel(findingId, admin.getName())));
    }

    @Operation(summary = "불일치 조치 — 키로 Mock 취소 일괄(최대 100건). be 가 취소 완료인 건만 받고(Mock 에만 있음은 한 건씩), "
            + "건마다 따로 처리해 건별 결과를 준다")
    @PostMapping("/findings/external-cancel")
    public ApiResponse<ExternalCancelBatchResponse> externalCancelAll(
            @Valid @RequestBody ExternalCancelBatchRequest request, Authentication admin) {
        return ApiResponse.ok(ExternalCancelBatchResponse.from(
                reconciliationAdminService.externalCancelAll(request.findingIds(), admin.getName())));
    }

    private static <T> ResponseEntity<ApiResponse<T>> accepted(T body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(body));
    }
}
