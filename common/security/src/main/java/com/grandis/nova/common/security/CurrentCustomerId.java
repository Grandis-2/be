package com.grandis.nova.common.security;

import io.swagger.v3.oas.annotations.Parameter;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 파라미터에 현재 회원의 customers.id(Long) 를 넣는다.
 * USER 토큰에서만 값이 있다. ADMIN 토큰으로 사용자 API 를 부르면 403 FORBIDDEN 이다 — 401 이 아니다. 인증은 됐고 권한이 아니다.
 * 토큰에서 오는 값이라 OpenAPI 문서의 요청 파라미터로 나오지 않게 숨긴다(숨기지 않으면 `customerId` 가 쿼리 인자로 나왔다 — 실측).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Parameter(hidden = true)
public @interface CurrentCustomerId {
}
