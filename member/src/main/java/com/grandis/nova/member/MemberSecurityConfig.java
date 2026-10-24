package com.grandis.nova.member;

import com.grandis.nova.common.security.SecurityFilterChainSupport;
import com.grandis.nova.common.web.OpenApiPaths;
import com.grandis.nova.member.auth.api.AuthCookies;
import com.grandis.nova.member.auth.api.RefreshOriginPolicy;
import com.grandis.nova.member.auth.application.AdminLoginLimit;
import com.grandis.nova.member.auth.application.AdminProperties;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
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
@EnableConfigurationProperties({AdminProperties.class, AdminLoginLimit.class, AuthCookies.Settings.class, RefreshOriginPolicy.Settings.class})
public class MemberSecurityConfig {

    @Bean
    SecurityFilterChain memberChain(HttpSecurity http, SecurityFilterChainSupport support) throws Exception {
        return support.build(http, a -> a
                // 실시간 연결(SSE)을 닫을 때 컨테이너의 재디스패치. 인증 필터가 건너뛰어 익명으로 보여, 막으면 이미 나간 응답 위에서 ERROR 가 남는다(실측).
                // 인가는 처음 REQUEST 디스패치가 이미 했다 — 비동기 엔드포인트(DeferredResult · Callable)를 더하면 그 경로의 규칙은 위가 아니라 아래에 둔다
                .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                .requestMatchers(EndpointRequest.to("health")).permitAll()                       // 헬스 체크(관리 포트의 readiness · liveness)
                .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()          // 공개키 게시(RFC 7517). 검증 서비스가 받아 간다
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/kakao/callback").permitAll()   // 로그인(프론트 주도 코드 교환)
                .requestMatchers(HttpMethod.POST, "/api/v1/admin/session").permitAll()          // 관리자 로그인
                .requestMatchers(HttpMethod.POST, "/api/v1/session/refresh").permitAll()        // F-X-01 재발급: 쿠키로 식별
                .requestMatchers(HttpMethod.DELETE, "/api/v1/session").permitAll()              // 로그아웃: 만료된 액세스로도 되어야 한다
                .requestMatchers(HttpMethod.GET, OpenApiPaths.docs()).permitAll()                 // OpenAPI 문서(허브)
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                // order · preorder 관리자 화면의 회원 이름(관리자 토큰을 싣는다). 메서드를 묶지 않는다 — HEAD 는 GET 핸들러로 가는데 GET 매처에는 안 걸린다(실측)
                .requestMatchers("/internal/customers/names").hasRole("ADMIN")
                .anyRequest().authenticated());                                                 // GET /session, /me/**, /notifications/**
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
