package com.grandis.nova.order.config;

import com.grandis.nova.common.security.SecurityFilterChainSupport;
import com.grandis.nova.common.web.OpenApiPaths;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * order 의 인가 규칙. 토큰 검증 · 401/403 봉투 · 세션 없음은 common:security 의 SecurityFilterChainSupport 가 한다.
 *
 * 이 서비스가 가진 경로만 적고 나머지는 거부한다(denyAll). 규칙 없이 추가된 엔드포인트가 열린 채 배포되지 않게 —
 * 새 경로는 여기 규칙을 더해야 테스트가 통과한다. 매핑 없는 경로도 404 가 아니라 401(익명) · 403(인증)이다.
 * 예외는 OpenAPI 문서 경로다 — GET 으로 열려 있고 문서가 꺼진 환경에서는 404 다. 헬스는 관리 포트에서만 토큰 없이 열린다.
 *
 * 폐기 조회 실패 때 닫는 경로(auth.revocation-check.fail-closed-paths)는 설정에 있다 — application.yml.example.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityFilterChainSupport support) throws Exception {
        return support.build(http, authorize -> authorize
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/orders/**").authenticated()
                .requestMatchers("/api/v1/cart", "/api/v1/cart/**").hasRole("USER")                  // 내 장바구니 — 회원만(관리자는 회원 id 가 없다)
                // 서비스 간 내부 API. 호출자(preorder · catalog)가 사용자 토큰을 Authorization: Bearer 로 그대로 싣는다 —
                // 경로는 인증만 요구하고, 역할 · 주인은 엔드포인트가 정한다(@CurrentViewer · @CurrentCustomerId).
                .requestMatchers("/internal/**").authenticated()
                .requestMatchers(EndpointRequest.to("health")).permitAll()                          // 헬스 체크(관리 포트의 readiness · liveness)
                .requestMatchers(HttpMethod.GET, OpenApiPaths.docs()).permitAll()                   // OpenAPI 문서
                .anyRequest().denyAll());
    }
}
