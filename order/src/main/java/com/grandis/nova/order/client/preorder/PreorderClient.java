package com.grandis.nova.order.client.preorder;

import com.grandis.nova.common.web.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * order → preorder 서비스 내부 API 호출 클라이언트(선언형 HTTP 인터페이스, 구현은 HttpClientConfig 가 만든다).
 * 예약은 preorder 에게 묻는다 — preorders 테이블을 직접 읽지 않는다(모듈 경계).
 *
 * 계약은 아직 없다(preorder 에 이 API 가 없고 contracts/preorder-internal.md 도 없다). 가정한 모양은
 * docs/plans/NV-55-order-create-plan.md §4-1 에 있다. 확정되면 경로 · 필드를 맞춘다.
 */
@HttpExchange("/internal/preorders")
public interface PreorderClient {

    /** 없는 예약이면 404 — 호출 쪽에서 빈 결과로 바꾼다. */
    @GetExchange("/{preorderToken}")
    ApiResponse<PreorderSnapshot> getPreorder(@PathVariable String preorderToken);
}
