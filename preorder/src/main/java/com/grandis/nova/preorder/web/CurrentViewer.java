package com.grandis.nova.preorder.web;

import com.grandis.nova.common.security.CurrentCustomerId;
import io.swagger.v3.oas.annotations.Parameter;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 파라미터에 {@link Viewer} 를 넣는다. USER · ADMIN 둘 다 받는 조회에서 쓴다.
 * 회원 것만 다루는 API 는 {@link CurrentCustomerId} 를 쓴다. 토큰에서 오는 값이라 문서의 요청 파라미터로 나오지 않게 숨긴다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Parameter(hidden = true)
public @interface CurrentViewer {
}
