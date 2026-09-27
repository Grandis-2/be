package com.grandis.nova.common.security;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 폐기 조회 실패 시 닫는 경로 설정을 바인딩하고 @CurrentCustomerId 리졸버를 MVC 에 등록한다.
 *
 * AuthenticationManager 빈은 인증에 쓰이지 않는다 — 토큰 검증은 JwtAuthenticationFilter 가 한다. 이 빈이 있는 이유는 Boot 의
 * UserDetailsServiceAutoConfiguration(4.x 에서 spring-boot-security 모듈)이 AuthenticationManager·AuthenticationProvider·
 * UserDetailsService·AuthenticationManagerResolver 중 하나라도 있으면 물러나기 때문이다. 없으면 서비스마다 "Using generated security
 * password" 와 인메모리 사용자 하나가 생긴다. 앱마다 exclude 를 다는 대신 한 곳에서 끈다. 실측: ConfigBindingTest.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RevocationCheckProperties.class)
public class AuthWebConfiguration implements WebMvcConfigurer {

    @Bean
    AuthenticationManager noAuthenticationManager() {
        return authentication -> {
            throw new AuthenticationServiceException("nova does not authenticate through AuthenticationManager; tokens are verified by JwtAuthenticationFilter");
        };
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentCustomerIdArgumentResolver());
    }
}
