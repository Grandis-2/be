package com.grandis.nova.order.client.preorder;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.web.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * order → preorder 서비스 내부 API 호출 클라이언트(선언형 HTTP 인터페이스, 구현은 HttpClientConfig 가 만든다).
 * 예약은 preorder 에게 묻는다 — preorders 테이블을 직접 읽지 않는다(모듈 경계).
 *
 * 계약: order-handoff.md 요청 2 (preorder InternalPreorderController). 사용자 액세스 토큰을 그대로 전달하고 preorder 가
 * 서명 · 만료 · 주인을 확인한다 — 토큰 문제 401, 남의 예약 403, 없는 예약 404(error.code PREORDER_NOT_FOUND).
 * 헤더는 common:security 의 인증 필터가 읽는 {@code Authorization: Bearer} 다({@link BearerTokens}, NV-137).
 */
@HttpExchange("/internal/preorders")
public interface PreorderClient {

    /**
     * @param preorderId    예약 공개 UUID
     * @param authorization 헤더 값 그대로({@link BearerTokens#value} 로 만든 "Bearer …"). null 이면 싣지 않는다(preorder 가 401)
     */
    @GetExchange("/{preorderId}/payability")
    ApiResponse<PreorderPayability> getPayability(@PathVariable String preorderId,
                                                  @RequestHeader(name = BearerTokens.HEADER, required = false)
                                                  String authorization);
}
