package com.grandis.nova.order.client.payment;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.web.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;
import java.util.UUID;

/**
 * order → payment 관리자 환불 조치 내부 API(선언형 HTTP 인터페이스). payment 는 ALB 에 없어 관리자 화면은 order 를 거친다.
 * 그룹은 payment-refund-admin 이다 — 주소는 payment 와 같고, 조치가 결제사 조회(최대 63초)를 기다려 읽기 기한을 70초로 둔다. DLQ 그룹(5s)으로는
 * 조회가 끝나기 전에 끊긴다.
 *
 * 계약: payment InternalRefundAdminController. 관리자 액세스 토큰을 그대로 전달하고, payment 는 역할(ADMIN)을 다시 보고 조치한 사람을 그
 * 토큰의 주체로 남긴다.
 *
 * @see PaymentRefundAdmin 실패 분류
 */
public interface PaymentRefundAdminClient {

    /** @param authorization 헤더 값 그대로("Bearer …"). null 이면 싣지 않는다(payment 가 401) */
    @GetExchange("/internal/refund-issues")
    ApiResponse<List<PaymentRefundIssue>> issues(@RequestParam(required = false) List<UUID> orderIds,
                                                 @RequestParam int staleHours,
                                                 @RequestHeader(name = BearerTokens.HEADER, required = false)
                                                 String authorization);

    @PostExchange("/internal/refunds/{orderId}/retry")
    ApiResponse<PaymentRefundAdminResult> retry(@PathVariable UUID orderId, @RequestBody PaymentRefundAdminRequest request,
                                                @RequestHeader(name = BearerTokens.HEADER, required = false)
                                                String authorization);

    @PostExchange("/internal/refunds/{orderId}/manual-complete")
    ApiResponse<PaymentRefundAdminResult> manualComplete(@PathVariable UUID orderId,
                                                         @RequestBody PaymentRefundAdminRequest request,
                                                         @RequestHeader(name = BearerTokens.HEADER, required = false)
                                                         String authorization);
}
