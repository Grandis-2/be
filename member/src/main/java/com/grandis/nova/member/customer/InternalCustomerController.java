package com.grandis.nova.member.customer;

import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.web.ApiResponse;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 서비스 간 내부 조회 — 다른 서비스는 customers 를 읽지 않는다. 계약: contracts/member-internal.md. /internal/** 은 공개 라우팅에서 빠져야 한다.
 * <ul>
 *     <li>{@code /me}: catalog 가 리뷰를 쓸 때 작성자 이름(실명 · 표시명). 호출한 서비스가 실은 사용자 토큰의 주인 정보만 돌려준다(경로에 회원 id 를
 *     받지 않는다 — 남의 정보를 물을 길을 만들지 않는다). 권한 USER — ADMIN 토큰은 @CurrentCustomerId 리졸버가 403 으로 막는다.</li>
 *     <li>{@code /names}: order · preorder 관리자 목록 · 상세의 회원 이름. 관리자 API(AdminCustomerController)와 같은 응답 · 같은 가림이다.
 *     권한 ADMIN(MemberSecurityConfig) — 관리자 요청의 토큰을 실어 부른다. 회원 토큰으로 남의 이름을 물을 수 없다.</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/customers")
public class InternalCustomerController {

    private final CustomerService customers;

    public InternalCustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping("/me")
    public ApiResponse<InternalCustomerResponse> me(@CurrentCustomerId UUID customerId) {
        CustomerService.ProfileView view = customers.profile(customerId);
        return ApiResponse.ok(new InternalCustomerResponse(customerId, view.displayName(), view.profile().name()));
    }

    /** ids 1~100개 — 관리자 목록 한 쪽의 최대 크기(order · preorder PageSizes.MAX)와 같다. 요청 순서, 같은 id 는 한 번, 없는 회원은 빠진다. */
    @GetMapping("/names")
    public ApiResponse<AdminCustomerController.AdminMembersResponse> names(
            @RequestParam @Size(min = 1, max = AdminCustomerController.MAX_IDS) List<@NotNull UUID> ids) {
        return ApiResponse.ok(new AdminCustomerController.AdminMembersResponse(customers.names(ids)));
    }

    /**
     * @param displayName 카카오 닉네임(늘 있다)
     * @param name        회원이 입력한 실명. 프로필을 안 채웠으면 null. 받는 쪽이 가려서 쓴다
     */
    public record InternalCustomerResponse(UUID customerId, String displayName, String name) {
    }
}
