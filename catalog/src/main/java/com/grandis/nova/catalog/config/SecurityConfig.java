package com.grandis.nova.catalog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * 최소 보안 설정 — 경로별 권한만 정한다. 토큰 검증 필터는 없다.
 *
 * 액세스 토큰 검증은 common:security 가 맡는다. 머지되면 그 필터가 SecurityContext 를 채우고
 * 이 체인을 그쪽 구성으로 바꾼다. 그때까지 운영에서는 인증된 요청이 없으므로 보호 경로는 401 이다.
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
                        // 내부 조회는 접수(USER)와 관리자 등록(ADMIN)이 같이 쓴다. 역할이 둘뿐이라 authenticated() 와 동작은 같지만 계약대로 적는다
                        .requestMatchers("/internal/**").hasAnyRole("USER", "ADMIN")
                        .anyRequest().permitAll())
                .build();
    }
}
