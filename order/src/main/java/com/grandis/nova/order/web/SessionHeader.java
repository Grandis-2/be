package com.grandis.nova.order.web;

/**
 * 사용자 세션 토큰(액세스 JWT)을 싣는 헤더. 값은 "Bearer " 접두어 없이 토큰 그대로다.
 * 받을 때(사용자 → order)와 보낼 때(order → preorder 내부 API) 같은 이름을 쓴다 — preorder 는 common:security 의
 * JwtAuthenticationFilter 로 이 헤더만 읽는다. 문자열을 여기 한 곳에만 둔다.
 *
 * common:security 도입 시: 이 파일을 지우고 JwtAuthenticationFilter.HEADER 로 바꾼다.
 */
public final class SessionHeader {

    public static final String NAME = "X-Session-Token";

    private SessionHeader() {
    }
}
