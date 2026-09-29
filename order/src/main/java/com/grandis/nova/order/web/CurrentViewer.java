package com.grandis.nova.order.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 파라미터에 {@link Viewer} 를 넣는다. USER · ADMIN 둘 다 받는 조회에서 쓴다.
 * 회원 것만 다루는 API 는 common:security 의 {@link com.grandis.nova.common.security.CurrentCustomerId} 를 쓴다.
 *
 * common:security 에는 이 애너테이션이 없다(CurrentCustomerId 만 있다). 그래서 order 에 둔다 — common:security 로 올릴지는
 * member 팀 결정이다. 리졸버는 같은 인증 주체(NovaAuthentication)를 읽는다({@link CurrentViewerArgumentResolver}).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentViewer {
}
