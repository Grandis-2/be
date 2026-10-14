package com.grandis.nova.member.customer;

import com.grandis.nova.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 관리자 회원 조회 — 관리자 예약 목록이 회원 id(customerId)만 주므로, 화면에 뜬 쪽의 id 들로 이름을 받는다. 권한 ADMIN(/api/v1/admin/** 규칙).
 * 회원 전체 목록은 주지 않는다 — 필요한 만큼의 개인정보만 내보낸다. 한 번에 최대 100개는 예약 목록 한 쪽의 최대 크기와 같다.
 */
@RestController
@RequestMapping("/api/v1/admin/members")
public class AdminCustomerController {

    static final int MAX_IDS = 100;

    private final CustomerService customers;

    public AdminCustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @Operation(summary = "회원 이름 일괄 조회", description = "ids 1~100개(반복 · 쉼표 모두). 요청 순서, 같은 id 는 한 번, 없는 회원은 빠진다. "
            + "name 은 실명이고 실명이 없으면 카카오 표시 이름")
    @GetMapping
    public ApiResponse<AdminMembersResponse> names(@RequestParam @Size(min = 1, max = MAX_IDS) List<@NotNull UUID> ids) {
        return ApiResponse.ok(new AdminMembersResponse(customers.names(ids)));
    }

    public record AdminMembersResponse(List<CustomerService.MemberName> items) {
    }
}
