package com.grandis.nova.waitingroom.auth;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/** jwt.jwk-set-uri 가 비어 있지 않을 때. 빈 값은 정적 공개키만 쓰는 구성이다. */
class JwkSetUriConfigured implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return StringUtils.hasText(context.getEnvironment().getProperty("jwt.jwk-set-uri"));
    }
}
