package com.grandis.nova.common.security;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * @CurrentCustomerId Long 을 SecurityContext 의 NovaAuthentication 에서 푼다.
 * 여기서 던지는 BusinessException 은 GlobalExceptionHandler 가 봉투로 낸다. 보안 필터 체인의 AccessDeniedException 을 던지면
 * MVC 의 예외 처리(@ExceptionHandler(Exception.class)) 가 먼저 잡아 500 이 되므로 그 길은 쓰지 않는다.
 * 실측: SecurityChainTest.adminTokenOnCustomerEndpointIs403.
 */
public class CurrentCustomerIdArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        if (!parameter.hasParameterAnnotation(CurrentCustomerId.class)) {
            return false;
        }
        Class<?> type = parameter.getParameterType();
        if (type != Long.class && type != long.class) {
            // 조용히 사양하면 스프링이 같은 이름의 쿼리 파라미터에 바인딩해 남의 id 를 받아들인다. 선언 오류는 기동·첫 호출에서 터뜨린다.
            throw new IllegalStateException("@CurrentCustomerId must be Long, was " + type.getName() + " on " + parameter.getMethod());
        }
        return true;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof NovaAuthentication nova)) {
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
        AuthenticatedPrincipal principal = nova.getPrincipal();
        if (principal.role() != Role.USER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        try {
            return principal.customerId();
        } catch (NumberFormatException e) {
            // USER 토큰의 sub 는 발급기가 customers.id 로만 만든다. 십진수가 아니면 우리 토큰이 아니거나 발급기 버그다. 500 이 아니라 401.
            throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
        }
    }
}
