package com.grandis.nova.catalog.integration;

import com.grandis.nova.common.web.ApiResponse;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.UUID;

/** member 내부 API — 리뷰 작성자 이름. 계약: contracts/member-internal.md. 토큰 주인의 정보만 돌려준다. */
@HttpExchange("/internal/customers")
public interface MemberClient {

    @GetExchange("/me")
    ApiResponse<Customer> getMe();

    /** @param name 회원이 입력한 실명. 프로필을 안 채웠으면 null */
    record Customer(UUID customerId, String displayName, String name) {

        /** 리뷰에 쓸 이름 — 실명이 있으면 실명, 없으면 카카오 닉네임(2026-10-07 결정). */
        public String reviewerName() {
            return name != null && !name.isBlank() ? name : displayName;
        }
    }
}
