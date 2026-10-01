package com.grandis.nova.preorder.integration.order;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.web.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * order 내부 API. 계약: contracts/preorder-internal.md 1.2.
 * 받은 사용자 토큰을 그대로 싣는다 — order 가 토큰 주인과 주문 회원이 같은지 확인한다.
 */
@HttpExchange("/internal/orders")
public interface OrderClient {

    /**
     * 취소를 시작하기 전에 "배송을 시작했나" 를 묻는다. 주문이 없으면 cancelable = true, orderStatus = null.
     * 경로는 예약 내부 id(orders.preorder_id 와 같은 값)다 — 공개 UUID 가 아니다.
     */
    @GetExchange("/by-preorder/{preorderInternalId}/cancelability")
    ApiResponse<Cancelability> getCancelability(@PathVariable Long preorderInternalId,
                                                @RequestHeader(value = BearerTokens.HEADER, required = false)
                                                String authorization);
}
