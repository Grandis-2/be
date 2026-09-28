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
    @DisplayName("기본 목록 — 실제 취소 경로 POST /api/v1/preorders/*/cancel 은 닫히고 옛 /reservations 경로는 목록 밖이다")
    void defaultsCloseTheRealCancelRoute() {
        RevocationFailurePolicy policy = new RevocationFailurePolicy(new RevocationCheckProperties(null));

        assertThat(policy.failClosed(request("POST", "/api/v1/preorders/42/cancel"))).isTrue();
        assertThat(policy.failClosed(request("POST", "/api/v1/orders/42/cancel"))).isTrue();
        assertThat(policy.failClosed(request("POST", "/api/v1/reservations/42/cancel"))).as("죽은 경로 — 실제 API 가 없다").isFalse();
        assertThat(policy.failClosed(request("GET", "/api/v1/preorders/42"))).isFalse();
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }
}
