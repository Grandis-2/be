package com.grandis.nova.preorder.syncjob;

import com.grandis.nova.common.web.ApiResponse;
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

/** 외부 동기화 작업 조회와 DEAD_LETTER 재처리. 재처리는 요청만 남기고 작업을 되돌리는 것은 worker 다. */
@Tag(name = "관리자 · 동기화 작업")
@RestController
@RequestMapping("/api/v1/admin/sync-jobs")
class AdminSyncJobController {

    private final SyncJobAdminService syncJobAdminService;

    AdminSyncJobController(SyncJobAdminService syncJobAdminService) {
        this.syncJobAdminService = syncJobAdminService;
    }

    @Operation(summary = "동기화 작업 목록")
    @GetMapping
    public ApiResponse<SyncJobListResponse> list(
            @RequestParam(required = false) SyncJobType jobType,
            @RequestParam(required = false) SyncJobStatus status,
            @RequestParam(required = false) String preorderId,
            @RequestParam(defaultValue = "false") boolean groupByError,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        return ApiResponse.ok(SyncJobListResponse.from(syncJobAdminService.find(jobType, status, preorderId,
                groupByError, PageSizes.requirePage(page), PageSizes.require(size))));
    }

    @Operation(summary = "동기화 작업 상세")
    @GetMapping("/{syncJobId}")
    public ApiResponse<SyncJobResponse> get(@PathVariable Long syncJobId) {
        return ApiResponse.ok(detail(syncJobAdminService.findOne(syncJobId)));
    }

    @Operation(summary = "DEAD_LETTER 작업 재처리 요청")
    @PostMapping("/{syncJobId}/reprocess")
    public ResponseEntity<ApiResponse<SyncJobSummaryResponse>> reprocess(@PathVariable Long syncJobId,
                                                                         Authentication admin) {
        return accepted(SyncJobSummaryResponse.from(syncJobAdminService.reprocess(syncJobId, admin.getName())));
    }

    @Operation(summary = "조건으로 일괄 재처리 요청")
    @PostMapping("/reprocess-batch")
    public ResponseEntity<ApiResponse<BatchReprocess>> reprocessBatch(
            @Valid @RequestBody ReprocessBatchRequest request, Authentication admin) {
        return accepted(syncJobAdminService.reprocessBatch(request.syncJobIds(), request.errorCodeFilter(),
                request.rateOrDefault(), admin.getName()));
    }

    private static SyncJobResponse detail(SyncJobView view) {
        return SyncJobResponse.from(view.job(), view.preorderToken(), view.attempts());
    }

    private static <T> ResponseEntity<ApiResponse<T>> accepted(T body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(body));
    }
}
