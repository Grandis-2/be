package com.grandis.nova.member.customer;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    public ProfileView profile(long customerId) {
        Customer customer = find(customerId);
        return new ProfileView(customer.getDisplayName(), customer.profile());
    }

    @Transactional
    public ProfileView changeProfile(long customerId, Profile profile) {
        Customer customer = find(customerId);
        customer.changeProfile(profile);
        return new ProfileView(customer.getDisplayName(), customer.profile());
    }

    @Transactional(readOnly = true)
    public ShippingAddress defaultAddress(long customerId) {
        return find(customerId).defaultAddress();
    }

    @Transactional
    public ShippingAddress changeDefaultAddress(long customerId, ShippingAddress address) {
        Customer customer = find(customerId);
        customer.changeDefaultAddress(address);
        return customer.defaultAddress();
    }

    /** 표시 이름은 카카오에서 온 읽기 전용 값이라 프로필과 같이 다닌다. */
    public record ProfileView(String displayName, Profile profile) {
    }

    private Customer find(long customerId) {
        return customers.findById(customerId).orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHENTICATED));
    }
}
