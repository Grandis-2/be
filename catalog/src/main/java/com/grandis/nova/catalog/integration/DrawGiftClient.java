package com.grandis.nova.catalog.integration;

import com.grandis.nova.common.web.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.UUID;

/**
 * order 내부 API — 상품이 진행 중인 드로우의 증정품인가(상품 삭제의 409 판정). 계약: contracts/order-internal.md.
 * 요청한 관리자의 토큰은 릴레이 인터셉터가 싣는다. order 는 관리자 토큰만 받는다 — 403 은 연동 오류다({@link InternalCalls}).
 */
@HttpExchange("/internal/draws")
public interface DrawGiftClient {

    @GetExchange("/gift-products/{productId}")
    ApiResponse<DrawGift> getGift(@PathVariable UUID productId);

    /** @param drawInProgress 그 상품을 증정품으로 둔 회차 중 반영 전인 것이 있다 */
    record DrawGift(UUID productId, Boolean drawInProgress) {
    }
}
