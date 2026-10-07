package com.grandis.nova.member.auth.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.member.auth.infrastructure.kakao.KakaoOAuthClient;
import com.grandis.nova.member.auth.infrastructure.kakao.KakaoUserInfo;
import com.grandis.nova.member.customer.Customer;
import com.grandis.nova.member.customer.CustomerRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 실제 DB 로는 만들 수 없는 갈래 — 조회한 회원 행이 표시명 UPDATE 전에 사라진 경우. */
@DisplayName("KakaoLoginService — 표시명을 바꾸는 사이 회원이 사라지면 토큰을 내주지 않는다")
class KakaoLoginServiceTest {

    @Test
    @DisplayName("표시명 UPDATE 가 0 행이면 401(없는 회원과 같은 규칙), 토큰 발급 없음")
    void vanishedCustomerGetsNoTokens() {
        KakaoOAuthClient kakao = mock(KakaoOAuthClient.class);
        CustomerRepository customers = mock(CustomerRepository.class);
        TokenService tokens = mock(TokenService.class);
        KakaoLoginService service = new KakaoLoginService(kakao, customers, tokens,
                Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC));
        when(kakao.exchangeCode("c", "r")).thenReturn("at");
        when(kakao.fetchUser("at")).thenReturn(new KakaoUserInfo("k1", "새 닉네임", null));
        when(customers.findByKakaoId("k1")).thenReturn(Optional.of(Customer.fromKakao("k1", "옛 닉네임")));
        when(customers.refreshDisplayName(any(), anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.login("c", "r")).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(CommonErrorCode.UNAUTHENTICATED);
        verify(tokens, never()).issue(anyString(), any());
    }
}
