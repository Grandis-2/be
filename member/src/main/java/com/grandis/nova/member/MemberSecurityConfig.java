package com.grandis.nova.member;

import com.grandis.nova.common.security.SecurityFilterChainSupport;
import com.grandis.nova.member.auth.api.AuthCookies;
import com.grandis.nova.member.auth.api.RefreshOriginPolicy;
import com.grandis.nova.member.auth.application.AdminProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * member 의 인가 규칙. 이 서비스가 가진 엔드포인트만 있다.
 * PasswordEncoder 는 관리자 비밀번호 검증용 하나. 회원 비밀번호는 없다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AdminProperties.class, AuthCookies.Settings.class, RefreshOriginPolicy.Settings.class})
public class MemberSecurityConfig {

    @Bean
    SecurityFilterChain memberChain(HttpSecurity http, SecurityFilterChainSupport support) throws Exception {
        return support.build(http, a -> a
                .requestMatchers("/health/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()          // 공개키 게시(RFC 7517). 검증 서비스가 받아 간다
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/kakao/callback").permitAll()   // 로그인(프론트 주도 코드 교환)
                .requestMatchers(HttpMethod.POST, "/api/v1/admin/session").permitAll()          // 관리자 로그인
                .requestMatchers(HttpMethod.POST, "/api/v1/session/refresh").permitAll()        // F-X-01 재발급: 쿠키로 식별
                .requestMatchers(HttpMethod.DELETE, "/api/v1/session").permitAll()              // 로그아웃: 만료된 액세스로도 되어야 한다
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated());                                                 // GET /session, /me/**
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
