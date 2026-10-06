package com.grandis.nova.catalog.integration;

import com.grandis.nova.common.web.ApiResponse;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/** member 내부 API — 리뷰 작성자 표시명. 계약: contracts/member-internal.md. 토큰 주인의 정보만 돌려준다. */
@HttpExchange("/internal/customers")
public interface MemberClient {

    @GetExchange("/me")
    ApiResponse<Customer> getMe();

    record Customer(Long customerId, String displayName) {
    }
}
