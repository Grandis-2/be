package com.grandis.nova.member.customer;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 내 정보(이름·이메일·연락처)와 기본 배송지의 조회·교체. 주소록은 없고 회원당 하나다. 기존 주문의 배송지는 주문이 복사해 가므로 여기 변경과 무관하다.
 * 토큰의 subject 가 가리키는 행이 없으면 401 — 발급기가 만든 id 인데 행이 없다는 것은 그 세션이 더 이상 회원을 가리키지 않는다는 뜻이다.
 * 지금은 도달하지 않는 갈래다: 탈퇴가 없어 customers 행은 지워지지 않는다. 탈퇴가 생기면 이 갈래가 살아난다.
 */
@Service
public class CustomerService {

    private final CustomerRepository customers;

    public CustomerService(CustomerRepository customers) {
        this.customers = customers;
    }

    @Transactional(readOnly = true)
    public ProfileView profile(UUID customerId) {
        Customer customer = find(customerId);
        return new ProfileView(customer.getDisplayName(), customer.profile());
    }

    @Transactional
    public ProfileView changeProfile(UUID customerId, Profile profile) {
        Customer customer = lock(customerId);
        customer.changeProfile(profile);
        return new ProfileView(customer.getDisplayName(), customer.profile());
    }

    @Transactional(readOnly = true)
    public ShippingAddress defaultAddress(UUID customerId) {
        return find(customerId).defaultAddress();
    }

    @Transactional
    public ShippingAddress changeDefaultAddress(UUID customerId, ShippingAddress address) {
        Customer customer = lock(customerId);
        customer.changeDefaultAddress(address);
        return customer.defaultAddress();
    }

    /**
     * 관리자 화면이 회원 id 를 이름으로 바꿀 때 — 요청 순서(같은 id 는 한 번), 없는 id 는 빠진다.
     * 이름은 실명, 실명이 없으면 카카오 표시 이름이다(리뷰 작성자명과 같은 규칙, 2026-10-07 결정). 실명은 가입 폼에서 받지만 비울 수도 있다
     * (PUT /me/profile 에 빈 값을 보내면 그 칸이 null 이 된다 — ProfileRequest).
     */
    @Transactional(readOnly = true)
    public List<MemberName> names(Collection<UUID> customerIds) {
        LinkedHashSet<UUID> ordered = new LinkedHashSet<>(customerIds);
        Map<UUID, Customer> found = customers.findAllById(ordered).stream().collect(Collectors.toMap(Customer::getId, Function.identity()));
        return ordered.stream().filter(found::containsKey).map(id -> MemberName.of(found.get(id))).toList();
    }

    public record MemberName(UUID memberId, String name) {
        static MemberName of(Customer customer) {
            // 앱이 쓰는 경로는 빈 문자열을 null 로 바꾸므로(ProfileRequest — 유일한 쓰기 경로) null 만 본다. DB CHECK 는 없다
            String name = customer.profile().name();
            return new MemberName(customer.getId(), name != null ? name : customer.getDisplayName());
        }
    }

    /** 표시 이름은 카카오에서 온 읽기 전용 값이라 프로필과 같이 다닌다. */
    public record ProfileView(String displayName, Profile profile) {
    }

    /** 쓰기 경로. 회원 행을 잠그고 읽어 같은 자원의 PUT 이 칸마다 섞이지 않게 한다({@link CustomerRepository#findForUpdate}). */
    private Customer lock(UUID customerId) {
        return customers.findForUpdate(customerId).orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHENTICATED));
    }

    private Customer find(UUID customerId) {
        return customers.findById(customerId).orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHENTICATED));
    }
}
