package com.grandis.nova.member.auth.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

@DisplayName("AdminLoginService — 환경변수 단일 관리자 계정")
class AdminLoginServiceTest {

    private static final String HASH = "$2a$12$" + "x".repeat(53);

    private PasswordEncoder encoder;
    private TokenService tokens;
    private AdminLoginService service;

    AdminCredentialRotationGuard guard;

    @BeforeEach
    void setUp() {
        encoder = mock(PasswordEncoder.class);
        tokens = mock(TokenService.class);
        guard = mock(AdminCredentialRotationGuard.class);
        service = new AdminLoginService(new AdminProperties("admin", HASH), encoder, tokens, guard);
    }

    @Test
    @DisplayName("username 이 틀려도 해시 비교를 한 번 한다 — 응답 시간으로 username 존재 여부를 흘리지 않는다")
    void wrongUsernameStillRunsMatches() {
        when(encoder.matches(any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.login("root", "pw", ClientInfo.UNKNOWN)).isInstanceOf(BusinessException.class);

        verify(encoder, times(1)).matches("pw", HASH);
        verify(tokens, never()).issue(any(), any(), any());
    }

    @Test
    @DisplayName("비밀번호가 틀리면 401, 발급 없음. null 비밀번호도 빈 문자열로 비교한다")
    void wrongPasswordIs401() {
        when(encoder.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.login("admin", "wrong", ClientInfo.UNKNOWN)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.login("admin", null, ClientInfo.UNKNOWN)).isInstanceOf(BusinessException.class);

        verify(encoder).matches("", HASH);
        verify(tokens, never()).issue(any(), any(), any());
    }

    @Test
    @DisplayName("둘 다 맞으면 sub=admin, role=ADMIN 으로 발급한다")
    void issuesAdminTokens() {
        when(encoder.matches("pw", HASH)).thenReturn(true);

        service.login("admin", "pw", ClientInfo.UNKNOWN);

        verify(tokens).issue(AdminLoginService.ADMIN_SUBJECT, Role.ADMIN, ClientInfo.UNKNOWN);
    }

    @Test
    @DisplayName("자격증명이 맞아도 이 인스턴스의 자격증명이 현재 것이 아니면(가드 거절) 발급하지 않는다. 틀리면 가드를 부르지도 않는다")
    void staleInstanceDoesNotIssue() {
        when(encoder.matches("pw", HASH)).thenReturn(true);
        org.mockito.Mockito.doThrow(new BusinessException(com.grandis.nova.common.CommonErrorCode.DEPENDENCY_UNAVAILABLE))
                .when(guard).requireCurrentForLogin();

        assertThatThrownBy(() -> service.login("admin", "pw", null)).isInstanceOf(BusinessException.class);
        verify(tokens, never()).issue(any(), any(), any());

        when(encoder.matches("wrong", HASH)).thenReturn(false);
        assertThatThrownBy(() -> service.login("admin", "wrong", null)).isInstanceOf(BusinessException.class);
        verify(guard, times(1)).requireCurrentForLogin();   // 틀린 비밀번호에서는 안 불렸다
    }
}
