package com.grandis.nova.preorder.web;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.security.AuthenticatedPrincipal;
import com.grandis.nova.common.security.NovaAuthentication;
import com.grandis.nova.common.security.Role;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** {@link CurrentViewer} 를 토큰의 인증 주체로 채운다. 관리자는 회원 id 가 없다. */
public class CurrentViewerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentViewer.class)
                && Viewer.class.equals(parameter.getParameterType());
    }

    @Override
    public Viewer resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof NovaAuthentication authentication)) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
        AuthenticatedPrincipal principal = authentication.getPrincipal();
        if (principal.role() == Role.ADMIN) {
            return new Viewer(null, true);
        }
        try {
            return new Viewer(principal.customerId(), false);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
    }
}
