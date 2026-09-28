package com.grandis.nova.preorder.web;

import com.grandis.nova.common.security.CurrentCustomerId;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 파라미터에 {@link Viewer} 를 넣는다. USER · ADMIN 둘 다 받는 조회에서 쓴다.
 * 회원 것만 다루는 API 는 {@link CurrentCustomerId} 를 쓴다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentViewer {
}
