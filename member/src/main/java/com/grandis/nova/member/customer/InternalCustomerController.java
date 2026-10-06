package com.grandis.nova.member.customer;

import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 내부 조회. catalog 가 리뷰를 쓸 때 작성자 표시명을 받는다 — catalog 는 customers 를 읽지 않는다. 계약: contracts/member-internal.md.
 * 호출한 서비스가 실은 사용자 토큰의 주인 정보만 돌려준다(경로에 회원 id 를 받지 않는다 — 남의 정보를 물을 길을 만들지 않는다).
 * 권한 USER — ADMIN 토큰은 @CurrentCustomerId 리졸버가 403 으로 막는다. /internal/** 은 공개 라우팅에서 빠져야 한다.
 */
@RestController
@RequestMapping("/internal/customers")
public class InternalCustomerController {

    private final CustomerService customers;

    public InternalCustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping("/me")
    public ApiResponse<InternalCustomerResponse> me(@CurrentCustomerId Long customerId) {
        return ApiResponse.ok(new InternalCustomerResponse(customerId, customers.profile(customerId).displayName()));
    }

    /** @param displayName 화면에 보이는 이름(카카오 닉네임). 받는 쪽이 가려서 쓴다 */
    public record InternalCustomerResponse(Long customerId, String displayName) {
    }
}
