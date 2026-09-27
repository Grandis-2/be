package com.grandis.nova.common.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * SecurityContext 에 들어가는 인증 객체. UsernamePasswordAuthenticationToken + UserDetails 대신 principal 하나만 든다.
 * 권한은 ROLE_USER / ROLE_ADMIN 하나뿐이라 hasRole("ADMIN") 이 그대로 읽는다(실측: SecurityChainTest).
 */
public final class NovaAuthentication extends AbstractAuthenticationToken {

    private final AuthenticatedPrincipal principal;

    public NovaAuthentication(AuthenticatedPrincipal principal) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
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
