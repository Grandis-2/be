package com.grandis.nova.order.order.api;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.web.ApiResponse;
import com.grandis.nova.order.client.payment.PaymentDeadLetter;
import com.grandis.nova.order.client.payment.PaymentDeadLetterBatch;
import com.grandis.nova.order.client.payment.PaymentDeadLetterBatchProgress;
import com.grandis.nova.order.client.payment.PaymentDeadLetterBatchRequest;
import com.grandis.nova.order.client.payment.PaymentDeadLetterDiscardRequest;
import com.grandis.nova.order.client.payment.PaymentDeadLetterFailureReason;
import com.grandis.nova.order.client.payment.PaymentDeadLetterStatus;
import com.grandis.nova.order.client.payment.PaymentDeadLetterSummary;
import com.grandis.nova.order.client.payment.PaymentDeadLetters;
import com.grandis.nova.order.web.PageSizes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * 결제 이벤트 큐(payment-events) DLQ 의 관리자 조회 · 되돌리기 · 버리기. payment 는 ALB 에 없어 order 가 받아 payment 내부 API 로 넘긴다 —
 * 경로 · 요청 · 응답은 payment 와 같다. 권한은 보안 설정이 경로로 막고(ADMIN), 관리자 토큰을 그대로 실어 payment 가 한 사람을 남긴다.
 */
@RestController
@RequestMapping(AdminPaymentDeadLetterController.BASE_PATH)
@Tag(name = "관리자 · 결제 이벤트 DLQ")
public class AdminPaymentDeadLetterController {

    static final String BASE_PATH = "/api/v1/admin/orders/payment-dead-letters";

    private final PaymentDeadLetters deadLetters;

    public AdminPaymentDeadLetterController(PaymentDeadLetters deadLetters) {
        this.deadLetters = deadLetters;
    }

    @GetMapping
    @Operation(summary = "결제 DLQ 메시지 목록", description = "최근 것부터. 기간은 쌓인 시각 [from, to). orderId 는 주문 이벤트의 주문 id 다")
    public ApiResponse<OffsetPage<PaymentDeadLetterSummary>> list(
            @RequestParam(required = false) PaymentDeadLetterStatus status,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) PaymentDeadLetterFailureReason failureReason,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageSizes.DEFAULT) int size,
            HttpServletRequest httpRequest) {
        int checkedSize = PageSizes.require(size);
        return ApiResponse.ok(deadLetters.list(status, eventType, failureReason, orderId, from, to,
                PageSizes.requirePage(page, checkedSize), checkedSize, token(httpRequest)));
    }

    @GetMapping("/{deadLetterId}")
    @Operation(summary = "결제 DLQ 메시지 상세", description = "원문과 되돌리기 · 버리기 기록")
    public ApiResponse<PaymentDeadLetter> get(@PathVariable UUID deadLetterId, HttpServletRequest httpRequest) {
        return ApiResponse.ok(deadLetters.get(deadLetterId, token(httpRequest)));
    }

    @PostMapping("/{deadLetterId}/redrive")
    @Operation(summary = "원래 큐로 되돌리기", description = "처리 결과는 상태로 남는다 — SUCCEEDED 는 메시지를 처리했다는 뜻이지 "
            + "환불을 열었다는 뜻이 아니다(이미 환불 · 마지막 환불 확정 실패면 열지 않고 끝난다. 환불 여부는 주문 상세의 결제로 본다). "
            + "REDRIVE_FAILED 는 또 DLQ 로 왔다는 뜻이다. 응답을 받지 못해 503 이어도 이미 보냈을 수 있다 — 상세의 상태로 확인한다")
    public ResponseEntity<ApiResponse<PaymentDeadLetterSummary>> redrive(@PathVariable UUID deadLetterId,
                                                                         HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(deadLetters.redrive(deadLetterId, token(httpRequest))));
    }

    @PostMapping("/redrive-batch")
    @Operation(summary = "조건으로 일괄 되돌리기", description = "한 번에 최대 1000건, 초당 1~200건(기본 20). 이어서 보내고, "
            + "진행은 응답의 batchId 로 redrive-batches/{batchId} 에서 본다")
    public ResponseEntity<ApiResponse<PaymentDeadLetterBatch>> redriveBatch(
            @Valid @RequestBody PaymentDeadLetterBatchRequest request, HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(deadLetters.redriveBatch(request, token(httpRequest))));
    }

    @GetMapping("/redrive-batches/{batchId}")
    @Operation(summary = "일괄 되돌리기 진행 — 보낸 수와 보낸 행의 지금 상태(처리됨 · 또 DLQ · 기다림)",
            description = "칸은 사전예약 일괄 재처리 진행과 같다. 결과 수는 조회할 때 큐로 보낸 원래 행의 지금 상태로 센다 — succeeded 는 "
                    + "처리됨(환불했다는 뜻은 아니다), failed 는 또 DLQ 로 옴이고, 다시 실패해 새로 쌓인 행은 세지 않는다. 보내기 결과를 "
                    + "남기지 못한 행은 실제로 갔을 수 있어도 unsent 로 센다. 결제 DLQ 는 보낸 행을 버리는 길이 없어 canceled 는 늘 0 이다. "
                    + "pending 은 아직 보내지 않았거나 결과를 기다리는 수다. finishedAt 이 비어 있는데 lastProgressAt 이 오래 그대로면 "
                    + "보내던 서버가 멈춘 것이다(같은 요청을 다시 보내면 이어진다). 진행 기록을 놓치면 finishedAt 이 찍혀도 pending 이 남을 "
                    + "수 있다. 없는 batchId, 하루가 지난 일괄, 시작 때 진행 기록을 남기지 못한 일괄은 404 다")
    public ApiResponse<PaymentDeadLetterBatchProgress> redriveBatchProgress(@PathVariable UUID batchId,
                                                                            HttpServletRequest httpRequest) {
        return ApiResponse.ok(deadLetters.redriveBatchProgress(batchId, token(httpRequest)));
    }

    @PostMapping("/{deadLetterId}/discard")
    @Operation(summary = "버리기", description = "사유 필수(500자 이하). 버린 메시지는 다시 오지 않는다 — order 에는 환불 요청을 다시 "
            + "보내는 길이 없어, 버린 환불 요청의 주문은 취소 중에 머문다. 결제 · 주문을 보고 직접 환불을 확인해야 한다")
    public ApiResponse<PaymentDeadLetter> discard(@PathVariable UUID deadLetterId,
                                                  @Valid @RequestBody PaymentDeadLetterDiscardRequest request,
                                                  HttpServletRequest httpRequest) {
        return ApiResponse.ok(deadLetters.discard(deadLetterId, request, token(httpRequest)));
    }

    private static String token(HttpServletRequest httpRequest) {
        return BearerTokens.extract(httpRequest).orElse(null);
    }
}
