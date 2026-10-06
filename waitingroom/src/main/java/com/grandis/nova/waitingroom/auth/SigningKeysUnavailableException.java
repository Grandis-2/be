package com.grandis.nova.waitingroom.auth;

import org.springframework.security.oauth2.jwt.JwtException;

/** 토큰을 검증할 키를 받지 못했다. 토큰 탓이 아니므로 401 이 아니라 503 이다. */
class SigningKeysUnavailableException extends JwtException {

    SigningKeysUnavailableException(Throwable cause) {
        super("signing keys unavailable", cause);
    }
}
