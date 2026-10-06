package com.grandis.nova.member.customer;

import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 정보 — 프로필(이름·이메일·연락처)과 기본 배송지. 권한 USER — ADMIN 토큰은 @CurrentCustomerId 리졸버가 403 FORBIDDEN 으로 막는다.
 * 둘 다 PUT 은 그 자원의 칸을 통째로 교체한다(부분 갱신 없음).
 */
@RestController
@RequestMapping("/api/v1/me")
public class CustomerController {

    private final CustomerService customers;

    public CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping("/profile")
    public ApiResponse<ProfileResponse> profile(@CurrentCustomerId Long customerId) {
        return ApiResponse.ok(view(customers.profile(customerId)));
    }

    @PutMapping("/profile")
    public ApiResponse<ProfileResponse> putProfile(@CurrentCustomerId Long customerId, @Valid @RequestBody ProfileRequest request) {
        return ApiResponse.ok(view(customers.changeProfile(customerId, request.toProfile())));
    }

    @GetMapping("/default-address")
    public ApiResponse<DefaultAddressResponse> get(@CurrentCustomerId Long customerId) {
        return ApiResponse.ok(new DefaultAddressResponse(customers.defaultAddress(customerId)));
    }

    @PutMapping("/default-address")
    public ApiResponse<DefaultAddressResponse> put(@CurrentCustomerId Long customerId, @Valid @RequestBody DefaultAddressRequest request) {
        return ApiResponse.ok(new DefaultAddressResponse(customers.changeDefaultAddress(customerId, request.toAddress())));
    }

    private static ProfileResponse view(CustomerService.ProfileView profile) {
        return ProfileResponse.of(profile.displayName(), profile.profile());
    }
}
