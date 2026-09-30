package com.grandis.nova.member.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName("ClientIps — X-Forwarded-For 에서 믿는 프록시가 붙인 칸만 쓴다")
class ClientIpsTest {

    @Test
    @DisplayName("프록시 2 개면 오른쪽에서 두 번째 — 왼쪽(클라이언트가 넣은 값)은 무시한다")
    void takesTheEntryAddedByTheOuterTrustedProxy() {
        assertThat(new ClientIps(2).of(request("spoof, 198.51.100.1, 203.0.113.9, 130.176.0.1"))).isEqualTo("203.0.113.9");
        assertThat(new ClientIps(1).of(request("spoof, 203.0.113.9"))).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("가장 흔한 모양 — 클라이언트가 헤더를 안 보내 칸이 정확히 둘(실제 IP, 엣지)이면 왼쪽이 실제 IP 다")
    void exactlyTwoEntriesIsTheCommonCase() {
        assertThat(new ClientIps(2).of(request("203.0.113.9, 130.176.0.1"))).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("헤더가 여러 줄이면 순서대로 이은 목록으로 본다")
    void multipleHeaderLinesAreOneList() {
        MockHttpServletRequest request = request("spoof");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 130.176.0.1");
        assertThat(new ClientIps(2).of(request)).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("칸이 모자라거나(프록시를 안 거침) 헤더가 없거나 0 이면 remoteAddr")
    void fallsBackToRemoteAddr() {
        assertThat(new ClientIps(2).of(request("203.0.113.9"))).isEqualTo("10.0.0.5");
        MockHttpServletRequest noHeader = new MockHttpServletRequest();
        noHeader.setRemoteAddr("10.0.0.5");
        assertThat(new ClientIps(2).of(noHeader)).isEqualTo("10.0.0.5");
        assertThat(new ClientIps(0).of(request("spoof, 203.0.113.9"))).isEqualTo("10.0.0.5");
        assertThatThrownBy(() -> new ClientIps(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("제한 설정 — 시도 수 · 창은 양수, 믿는 프록시 수는 0 이상이어야 기동한다")
    void limitSettingsAreValidated() {
        assertThatThrownBy(() -> new com.grandis.nova.member.auth.application.AdminLoginLimit(0, java.time.Duration.ofMinutes(1), 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new com.grandis.nova.member.auth.application.AdminLoginLimit(5, java.time.Duration.ZERO, 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new com.grandis.nova.member.auth.application.AdminLoginLimit(5, java.time.Duration.ofMinutes(1), -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new com.grandis.nova.member.auth.application.AdminLoginLimit(5, java.time.Duration.ofMinutes(1), 0).trustedProxyHops()).isZero();
    }

    private static MockHttpServletRequest request(String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }
}
