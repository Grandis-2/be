package com.grandis.nova.common.security;

/** 토큰 한 장의 용도. API 인증에는 ACCESS 만 쓰이고, REFRESH 는 재발급 경로에서만 받는다. */
public enum TokenType {
    ACCESS,
    REFRESH
}
