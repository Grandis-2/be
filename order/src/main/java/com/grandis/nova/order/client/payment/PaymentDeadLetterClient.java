package com.grandis.nova.order.client.payment;

import com.grandis.nova.common.OffsetPage;
import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.web.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.time.Instant;
import java.util.UUID;

/**
 * order → payment 관리자 DLQ 내부 API(선언형 HTTP 인터페이스). payment 는 ALB 에 없어 관리자 화면은 order 를 거친다.
 * 그룹은 payment-admin 이다 — 주소는 payment 와 같고, 읽기 기한(5s)이 payment 의 SQS 보내기 한 번(api-call-timeout 3s)보다 길어야
 * 되돌리기가 보내졌는데 503 으로 보이는 일이 줄어든다. 준비 · 승인 그룹의 기한은 따로 둔다.
 *
 * 계약: payment InternalDeadLetterController(일괄 되돌리기 진행의 칸 뜻은 payment BatchRedriveProgress). 관리자 액세스 토큰을 그대로
 * 전달하고, payment 는 역할(ADMIN)을 다시 보고 되돌리기 · 버리기를 한 사람을 그 토큰의 주체로 남긴다.
 *
 * @see PaymentDeadLetters 실패 분류
 */
@HttpExchange("/internal/dead-letters")
public interface PaymentDeadLetterClient {

    /** @param authorization 헤더 값 그대로({@link BearerTokens#value} 로 만든 "Bearer …"). null 이면 싣지 않는다(payment 가 401) */
    @GetExchange
    ApiResponse<OffsetPage<PaymentDeadLetterSummary>> list(
            @RequestParam(required = false) PaymentDeadLetterStatus status,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) PaymentDeadLetterFailureReason failureReason,
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam int page,
            @RequestParam int size,
            @RequestHeader(name = BearerTokens.HEADER, required = false) String authorization);

    @GetExchange("/{deadLetterId}")
    ApiResponse<PaymentDeadLetter> get(@PathVariable UUID deadLetterId,
                                       @RequestHeader(name = BearerTokens.HEADER, required = false) String authorization);

    @PostExchange("/{deadLetterId}/redrive")
    ApiResponse<PaymentDeadLetterSummary> redrive(@PathVariable UUID deadLetterId,
                                                  @RequestHeader(name = BearerTokens.HEADER, required = false)
                                                  String authorization);

    @PostExchange("/redrive-batch")
    ApiResponse<PaymentDeadLetterBatch> redriveBatch(@RequestBody PaymentDeadLetterBatchRequest request,
                                                     @RequestHeader(name = BearerTokens.HEADER, required = false)
                                                     String authorization);

    @GetExchange("/redrive-batches/{batchId}")
    ApiResponse<PaymentDeadLetterBatchProgress> redriveBatchProgress(@PathVariable UUID batchId,
                                                                     @RequestHeader(name = BearerTokens.HEADER,
                                                                             required = false) String authorization);

    @PostExchange("/{deadLetterId}/discard")
    ApiResponse<PaymentDeadLetter> discard(@PathVariable UUID deadLetterId,
                                           @RequestBody PaymentDeadLetterDiscardRequest request,
                                           @RequestHeader(name = BearerTokens.HEADER, required = false)
                                           String authorization);
}
