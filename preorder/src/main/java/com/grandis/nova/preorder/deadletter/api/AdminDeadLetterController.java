package com.grandis.nova.preorder.deadletter.api;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.preorder.deadletter.application.BatchRedrive;
import com.grandis.nova.preorder.deadletter.application.DeadLetterAdminService;
import com.grandis.nova.preorder.deadletter.domain.DeadLetterStatus;
import com.grandis.nova.preorder.deadletter.domain.FailureReason;
import com.grandis.nova.preorder.web.PageSizes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.Instant;
import java.util.UUID;

/** 이벤트 큐 DLQ 에서 옮겨 온 메시지 조회 · 되돌리기 · 버리기. */
@Tag(name = "관리자 · 이벤트 DLQ")
@RestController
@RequestMapping("/api/v1/admin/event-dlq")
class AdminDeadLetterController {

    private final DeadLetterAdminService deadLetterAdminService;

    AdminDeadLetterController(DeadLetterAdminService deadLetterAdminService) {
        this.deadLetterAdminService = deadLetterAdminService;
    }

    @Operation(summary = "DLQ 메시지 목록 — 기간은 쌓인 시각 [from, to)")
    @GetMapping
    public ApiResponse<OffsetPage<DeadLetterSummaryResponse>> list(
            @RequestParam(required = false) DeadLetterStatus status,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) FailureReason failureReason,
            @RequestParam(required = false) String preorderId,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        return ApiResponse.ok(deadLetterAdminService.find(status, eventType, failureReason, preorderId, customerId,
                from, to, PageSizes.requirePage(page), PageSizes.require(size)).map(DeadLetterSummaryResponse::from));
    }

    @Operation(summary = "DLQ 메시지 상세 — 원문과 되돌리기 · 버리기 기록")
    @GetMapping("/{deadLetterId}")
    public ApiResponse<DeadLetterResponse> get(@PathVariable UUID deadLetterId) {
        return ApiResponse.ok(DeadLetterResponse.from(deadLetterAdminService.findOne(deadLetterId)));
    }

    @Operation(summary = "원래 큐로 되돌리기 — 처리 결과는 상태(SUCCEEDED · REDRIVE_FAILED)로 남는다")
    @PostMapping("/{deadLetterId}/redrive")
    public ResponseEntity<ApiResponse<DeadLetterSummaryResponse>> redrive(@PathVariable UUID deadLetterId,
                                                                          Authentication admin) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(
                DeadLetterSummaryResponse.from(deadLetterAdminService.redrive(deadLetterId, admin.getName()))));
    }

    @Operation(summary = "조건으로 일괄 되돌리기 — 초당 건수 제한, 시작만 알린다")
    @PostMapping("/redrive-batch")
    public ResponseEntity<ApiResponse<BatchRedrive>> redriveBatch(@Valid @RequestBody RedriveBatchRequest request,
                                                                  Authentication admin) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(deadLetterAdminService.redriveBatch(
                request.deadLetterIds(), request.eventType(), request.failureReason(), request.rateOrDefault(),
                admin.getName())));
    }

    @Operation(summary = "버리기 — 사유 필수. 메시지가 틀린 것이면 보낸 쪽이 다시 발행한다")
    @PostMapping("/{deadLetterId}/discard")
    public ApiResponse<DeadLetterResponse> discard(@PathVariable UUID deadLetterId,
                                                   @Valid @RequestBody DiscardRequest request, Authentication admin) {
        return ApiResponse.ok(DeadLetterResponse.from(
                deadLetterAdminService.discard(deadLetterId, admin.getName(), request.note())));
    }
}
