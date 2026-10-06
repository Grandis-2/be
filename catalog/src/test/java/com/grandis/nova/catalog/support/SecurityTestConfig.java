package com.grandis.nova.catalog.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/** 토큰 검증 공개키(AccessTokens 의 키)를 앱에 잇는다. 앱은 발급하지 않고 이 공개키로 검증만 한다(운영의 JWKS 자리). */
@TestConfiguration(proxyBeanMethods = false)
public class SecurityTestConfig {

    @Bean
    DynamicPropertyRegistrar securityProperties() {
        return registry -> registry.add("jwt.public-keys." + AccessTokens.KEY_ID, AccessTokens::publicKeyPem);
    }
}
