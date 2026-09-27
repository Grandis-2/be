package com.grandis.nova.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 파라미터에 현재 회원의 customers.id(Long) 를 넣는다.
 * USER 토큰에서만 값이 있다. ADMIN 토큰으로 사용자 API 를 부르면 403 FORBIDDEN 이다 — 401 이 아니다. 인증은 됐고 권한이 아니다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentCustomerId {
}
