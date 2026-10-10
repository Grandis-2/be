package com.grandis.nova.order.client.catalog;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.web.ApiResponse;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.Collection;
import java.util.UUID;

/**
 * order → catalog 내부 API 호출 클라이언트(선언형 HTTP 인터페이스, 구현은 HttpClientConfig 가 만든다).
 * 상품 · 옵션 정보는 catalog 에게 묻는다 — catalog 표를 직접 읽지 않는다(모듈 경계).
 *
 * 계약: contracts/order-internal.md 의 GET /internal/options. 사용자 액세스 토큰을 그대로 전달하고 catalog 가 검증한다.
 */
@HttpExchange("/internal/options")
public interface CatalogClient {

    /**
     * @param ids           옵션 id 1~50개. 없는 옵션은 결과에서 빠진다
     * @param authorization 헤더 값 그대로({@link BearerTokens#value} 로 만든 "Bearer …"). null 이면 싣지 않는다(catalog 가 401)
     */
    @GetExchange
    ApiResponse<CatalogOptions> getOptions(@RequestParam("ids") Collection<UUID> ids,
                                           @RequestHeader(name = BearerTokens.HEADER, required = false) String authorization);
}
