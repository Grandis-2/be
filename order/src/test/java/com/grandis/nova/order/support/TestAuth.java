package com.grandis.nova.order.support;

import com.grandis.nova.common.security.AuthenticatedPrincipal;
import com.grandis.nova.common.security.NovaAuthentication;
import com.grandis.nova.common.security.Role;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

/**
 * MockMvc 인증 흉내. 인증 필터가 컨텍스트에 넣는 것과 같은 {@link NovaAuthentication} 을 직접 넣는다 — 토큰 · 폐기 조회는 건너뛴다.
 * 필터 자체(Bearer 헤더 · 폐기 조회 실패 정책)는 진짜 토큰으로 SecurityRulesTest 가 본다.
 *
 * user(...) 흉내는 쓰지 않는다. common:security 의 리졸버는 NovaAuthentication 만 인증으로 보므로 401 이 된다.
 */
public final class TestAuth {

    private TestAuth() {
    }

    public static RequestPostProcessor customer(Long customerId) {
        return authentication(new NovaAuthentication(new AuthenticatedPrincipal(customerId.toString(), Role.USER)));
    }

    public static RequestPostProcessor admin() {
        return authentication(new NovaAuthentication(new AuthenticatedPrincipal("admin", Role.ADMIN)));
    }
}
