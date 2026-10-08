package com.grandis.nova.order.web;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.security.AuthenticatedPrincipal;
import com.grandis.nova.common.security.NovaAuthentication;
import com.grandis.nova.common.security.Role;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@link CurrentViewer} 를 채운다. 인증 주체는 common:security 의 {@link NovaAuthentication} 하나만 본다 —
 * 같은 모듈의 @CurrentCustomerId 리졸버(common:security CurrentCustomerIdArgumentResolver)와 판정이 갈리지 않게 같은 주체에서 읽는다.
 * 그 밖의 인증 객체(익명 포함)는 401 이다.
 */
public class CurrentViewerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentViewer.class)
                && Viewer.class.equals(parameter.getParameterType());
    }

    @Override
    public Viewer resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        return current();
    }

    /** SecurityContext 의 인증 주체. ADMIN 은 회원 id 가 없다. */
    static Viewer current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof NovaAuthentication nova)) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
        AuthenticatedPrincipal principal = nova.getPrincipal();
        if (principal.role() == Role.ADMIN) {
            return new Viewer(null, true);
        }
        try {
            return new Viewer(principal.customerId(), false);
        } catch (IllegalArgumentException e) {
            // USER 토큰의 sub 는 customers.id 다. UUID 가 아니면 우리 토큰이 아니다 — 500 이 아니라 401(공통 리졸버와 같다).
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
    }
}
