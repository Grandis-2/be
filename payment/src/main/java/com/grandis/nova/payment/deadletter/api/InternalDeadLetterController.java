package com.grandis.nova.payment.deadletter.api;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.payment.deadletter.application.BatchRedrive;
import com.grandis.nova.payment.deadletter.application.DeadLetterAdminService;
import com.grandis.nova.payment.deadletter.domain.DeadLetterStatus;
import com.grandis.nova.payment.deadletter.domain.FailureReason;
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

/**
 * payment-events DLQ 에서 옮겨 온 메시지 조회 · 되돌리기 · 버리기(내부 API). payment 는 ALB 에 없어 관리자 화면은 order 가 받아
 * 관리자 토큰을 그대로 실어 부른다(Authorization: Bearer). 역할(ADMIN)은 보안 설정이 경로로 막고, 누가 했는지는 그 토큰의 주체다.
 */
@RestController
@RequestMapping("/internal/dead-letters")
class InternalDeadLetterController {

    private final DeadLetterAdminService deadLetterAdminService;

    InternalDeadLetterController(DeadLetterAdminService deadLetterAdminService) {
        this.deadLetterAdminService = deadLetterAdminService;
    }

    /** 최근 것부터. 기간은 쌓인 시각 [from, to). orderId 는 주문 이벤트(aggregate ORDER)의 주문 id 다. */
    @GetMapping
    public ApiResponse<OffsetPage<DeadLetterSummaryResponse>> list(
            @RequestParam(required = false) DeadLetterStatus status,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) FailureReason failureReason,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size) {
        int checkedSize = PageSizes.require(size);
        return ApiResponse.ok(deadLetterAdminService.find(status, eventType, failureReason, orderId, from, to,
                PageSizes.requirePage(page, checkedSize), checkedSize).map(DeadLetterSummaryResponse::from));
    }

    /** 원문과 되돌리기 · 버리기 기록. */
    @GetMapping("/{deadLetterId}")
    public ApiResponse<DeadLetterResponse> get(@PathVariable UUID deadLetterId) {
        return ApiResponse.ok(DeadLetterResponse.from(deadLetterAdminService.findOne(deadLetterId)));
    }

    /**
     * 원래 큐로 되돌린다. 처리 결과는 상태로 남는다 — SUCCEEDED 는 메시지를 처리했다는 뜻이지 환불을 열었다는 뜻이 아니다(이미 환불 ·
     * 확정 실패면 열지 않고 끝난다), REDRIVE_FAILED 는 또 DLQ 로 왔다는 뜻이다.
     */
    @PostMapping("/{deadLetterId}/redrive")
    public ResponseEntity<ApiResponse<DeadLetterSummaryResponse>> redrive(@PathVariable UUID deadLetterId,
                                                                          Authentication admin) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(
                DeadLetterSummaryResponse.from(deadLetterAdminService.redrive(deadLetterId, admin.getName()))));
    }

    /** 조건으로 일괄 되돌린다. 초당 건수를 제한하고 시작만 알린다. */
    @PostMapping("/redrive-batch")
    public ResponseEntity<ApiResponse<BatchRedrive>> redriveBatch(@Valid @RequestBody RedriveBatchRequest request,
                                                                  Authentication admin) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(deadLetterAdminService.redriveBatch(
                request.deadLetterIds(), request.eventType(), request.failureReason(), request.rateOrDefault(),
                admin.getName())));
    }

    /**
     * 버린다. 사유 필수. 버린 메시지는 다시 오지 않는다 — order 에는 환불 요청을 다시 보내는 길이 없어, 버린 환불 요청의 주문은 취소 중에
     * 머문다. 사람이 결제 · 주문을 보고 직접 환불을 확인해야 하고, 버리기는 ERROR 로그 · payment.dlq.discarded 로 드러난다.
     */
    @PostMapping("/{deadLetterId}/discard")
    public ApiResponse<DeadLetterResponse> discard(@PathVariable UUID deadLetterId,
                                                   @Valid @RequestBody DiscardRequest request, Authentication admin) {
        return ApiResponse.ok(DeadLetterResponse.from(
                deadLetterAdminService.discard(deadLetterId, admin.getName(), request.note())));
    }
}
