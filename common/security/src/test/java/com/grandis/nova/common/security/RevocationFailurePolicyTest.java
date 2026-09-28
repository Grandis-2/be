package com.grandis.nova.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RevocationFailurePolicy — 닫는 경로 판정")
class RevocationFailurePolicyTest {

    @Test
    @DisplayName("메서드가 붙은 항목은 그 메서드만 닫고, 없는 항목은 모든 메서드를 닫는다")
    void methodPrefixScopesTheMatch() {
        RevocationFailurePolicy policy = new RevocationFailurePolicy(new RevocationCheckProperties(
                List.of("POST /api/v1/preorders/*/cancel", "/api/v1/me/**")));

        assertThat(policy.failClosed(request("POST", "/api/v1/preorders/42/cancel"))).isTrue();
        assertThat(policy.failClosed(request("GET", "/api/v1/preorders/42/cancel"))).isFalse();
        assertThat(policy.failClosed(request("POST", "/api/v1/preorders"))).as("접수는 목록 밖 — 여는 쪽(D-2)").isFalse();
        assertThat(policy.failClosed(request("GET", "/api/v1/me/default-address"))).isTrue();
        assertThat(policy.failClosed(request("PUT", "/api/v1/me/default-address"))).isTrue();
    }

    @Test
    @DisplayName("공통 기본 목록은 인증 소유 경로 셋만 닫는다 — 도메인 경로는 서비스 설정이 닫는다")
    void defaultsCloseOnlyAuthOwnedPaths() {
        RevocationFailurePolicy policy = new RevocationFailurePolicy(new RevocationCheckProperties(null));

        assertThat(policy.failClosed(request("POST", "/api/v1/admin/preorders/42/cancel"))).isTrue();
        assertThat(policy.failClosed(request("POST", "/api/v1/session/refresh"))).isTrue();
        assertThat(policy.failClosed(request("PUT", "/api/v1/me/default-address"))).isTrue();
        assertThat(policy.failClosed(request("POST", "/api/v1/preorders/42/cancel"))).as("사전예약 취소는 preorder 의 설정 몫").isFalse();
        assertThat(policy.failClosed(request("POST", "/api/v1/orders/42/cancel"))).as("주문 취소는 order 의 설정 몫").isFalse();
    }

    @Test
    @DisplayName("서비스 설정 모양 — 주문 서비스가 적을 목록: 취소 · 결제 · 배송지 변경은 닫히고 주문 조회 · 옛 /reservations 경로는 열린다")
    void orderServiceListClosesItsIrreversiblePaths() {
        RevocationFailurePolicy policy = new RevocationFailurePolicy(new RevocationCheckProperties(List.of(
                "/api/v1/admin/**", "/api/v1/orders/*/payment-attempts/**", "POST /api/v1/orders/*/cancel",
                "PATCH /api/v1/orders/*/shipping-address")));

        assertThat(policy.failClosed(request("POST", "/api/v1/orders/42/cancel"))).isTrue();
        assertThat(policy.failClosed(request("POST", "/api/v1/orders/42/payment-attempts"))).isTrue();
        assertThat(policy.failClosed(request("PATCH", "/api/v1/orders/42/shipping-address"))).as("배송지 변경은 개인정보 — 닫힌다").isTrue();
        assertThat(policy.failClosed(request("GET", "/api/v1/orders/42"))).as("주문 조회는 열린다").isFalse();
        assertThat(policy.failClosed(request("POST", "/api/v1/reservations/42/cancel"))).as("죽은 경로 — 실제 API 가 없다").isFalse();
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }
}
