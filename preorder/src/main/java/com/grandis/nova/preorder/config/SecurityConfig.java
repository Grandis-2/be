package com.grandis.nova.preorder.config;

import com.grandis.nova.common.security.SecurityFilterChainSupport;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 경로별 권한. 토큰 검증 · 401/403 봉투는 common:security 가 맡는다. 적지 않은 경로는 거부한다.
 * 관리 엔드포인트는 앱과 다른 포트라 외부에서 닿지 않으므로 health · prometheus 는 인증 없이 연다.
 * 폐기 확인 실패 시 막는 경로는 preorder-defaults.properties 에 있다.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityFilterChainSupport support) throws Exception {
        return support.build(http, authorize -> authorize
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(EndpointRequest.to("health", "prometheus")).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/products/*/shipment-batches").permitAll()
                // API 문서. dev 프로파일에서만 켜지고, 꺼진 환경에는 경로가 없어 404 다
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/preorders/**", "/internal/**").authenticated()
                .anyRequest().denyAll());
    }
}
