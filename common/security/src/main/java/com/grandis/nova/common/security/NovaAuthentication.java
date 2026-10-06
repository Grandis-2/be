package com.grandis.nova.common.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * SecurityContext 에 들어가는 인증 객체. UsernamePasswordAuthenticationToken + UserDetails 대신 principal 하나만 든다.
 * 권한은 ROLE_USER / ROLE_ADMIN 하나뿐이라 hasRole("ADMIN") 이 그대로 읽는다(실측: SecurityChainTest).
 * 검증한 원본 액세스 토큰을 credentials 로 든다 — 내부 호출이 사용자 토큰을 그대로 넘길 때 꺼낸다({@link BearerTokenRelayInterceptor}).
 */
public final class NovaAuthentication extends AbstractAuthenticationToken {

    private final AuthenticatedPrincipal principal;
    private final String accessToken;

    /** 원본 토큰 없이(시험 · 넘길 필요 없는 곳). 내부 호출에 토큰이 실리지 않는다. */
    public NovaAuthentication(AuthenticatedPrincipal principal) {
        this(principal, null);
    }

    /** @param accessToken 검증한 원본 액세스 토큰(Bearer 접두 없이) */
    public NovaAuthentication(AuthenticatedPrincipal principal, String accessToken) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
        this.principal = principal;
        this.accessToken = accessToken;
        setAuthenticated(true);
    }

    /** 원본 액세스 토큰. 없으면 null. toString() · 로그에는 싣지 않는다. */
    @Override
    public String getCredentials() {
        return accessToken;
    }

    @Override
    public AuthenticatedPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal.subject();
    }
}
