package com.grandis.nova.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * 경로별 권한만 정한다. 토큰 검증 필터가 없어 보호 경로는 항상 401 이다.
 * common:security(#14) 머지 후 SecurityFilterChainSupport 로 교체한다.
 *
 * common:security 도입 시: 체인 본문을 SecurityFilterChainSupport.build(http, authorize -> ...) 로 바꾸고 아래 경로 규칙만
 * 넘긴다. 401 · 403 응답은 그쪽 JsonAuthFailureHandlers 가 봉투로 낸다(HttpStatusEntryPoint 를 지운다).
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling ->
                        handling.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/orders/**").authenticated()
                        // 서비스 간 내부 API. 호출자가 사용자 토큰을 그대로 싣는다 — 회원 · 관리자 모두, 주인 확인은 유스케이스가 한다.
                        // 아래 permitAll 로 떨어지면 인증 없이 열린다.
                        .requestMatchers("/internal/**").authenticated()
                        .anyRequest().permitAll())
                .build();
    }
}
