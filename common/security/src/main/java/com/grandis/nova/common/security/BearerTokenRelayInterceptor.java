package com.grandis.nova.common.security;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

/**
 * 내부 호출에 지금 요청한 사용자의 토큰을 그대로 싣는다(토큰 릴레이). 받는 서비스가 토큰 주인을 확인한다.
 * 보안 맥락에 토큰이 없거나(스케줄러 · 큐 소비기) 요청에 Authorization 이 이미 있으면 그대로 보낸다.
 * 서비스가 HTTP 클라이언트 그룹에 붙여 쓴다. 다른 스레드로 넘길 때는 보안 맥락을 복사하는 실행기를 쓴다.
 */
public class BearerTokenRelayInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        if (!request.getHeaders().containsHeader(BearerTokens.HEADER)) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof NovaAuthentication nova && nova.getCredentials() != null) {
                request.getHeaders().set(BearerTokens.HEADER, BearerTokens.value(nova.getCredentials()));
            }
        }
        return execution.execute(request, body);
    }
}
