package com.grandis.nova.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class BearerTokenRelayInterceptorTest {

    private final BearerTokenRelayInterceptor interceptor = new BearerTokenRelayInterceptor();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private HttpRequest send(MockClientHttpRequest request) throws Exception {
        HttpRequest[] sent = new HttpRequest[1];
        interceptor.intercept(request, new byte[0], (req, body) -> {
            sent[0] = req;
            return new MockClientHttpResponse();
        });
        return sent[0];
    }

    private MockClientHttpRequest request() {
        return new MockClientHttpRequest(HttpMethod.GET, URI.create("http://catalog/internal/products/1/options"));
    }

    @Test
    @DisplayName("지금 요청한 사용자의 토큰을 Bearer 로 싣는다")
    void relaysCurrentUserToken() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new NovaAuthentication(new AuthenticatedPrincipal("101", Role.USER), "access-token"));

        assertThat(send(request()).getHeaders().getFirst(BearerTokens.HEADER)).isEqualTo("Bearer access-token");
    }

    @Test
    @DisplayName("보안 맥락이 없거나 토큰이 없으면 싣지 않는다 — 스케줄러 · 큐 소비기")
    void sendsNothingWithoutToken() throws Exception {
        assertThat(send(request()).getHeaders().containsHeader(BearerTokens.HEADER)).isFalse();

        SecurityContextHolder.getContext().setAuthentication(
                new NovaAuthentication(new AuthenticatedPrincipal("101", Role.USER)));
        assertThat(send(request()).getHeaders().containsHeader(BearerTokens.HEADER)).isFalse();
    }

    @Test
    @DisplayName("호출하는 쪽이 이미 실은 Authorization 은 덮지 않는다")
    void keepsExplicitAuthorization() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new NovaAuthentication(new AuthenticatedPrincipal("101", Role.USER), "access-token"));
        MockClientHttpRequest request = request();
        request.getHeaders().set(BearerTokens.HEADER, "Bearer explicit");

        assertThat(send(request).getHeaders().getFirst(BearerTokens.HEADER)).isEqualTo("Bearer explicit");
    }
}
