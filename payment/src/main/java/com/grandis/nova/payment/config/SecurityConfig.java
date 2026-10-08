package com.grandis.nova.payment.config;

import com.grandis.nova.common.security.SecurityFilterChainSupport;
import com.grandis.nova.common.web.OpenApiPaths;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * payment 의 인가 규칙. 토큰 검증 · 401/403 봉투 · 세션 없음은 common:security 의 SecurityFilterChainSupport 가 한다.
 *
 * 이 서비스가 가진 경로만 적고 나머지는 거부한다(denyAll). 규칙 없이 추가된 엔드포인트가 열린 채 배포되지 않게 —
 * 새 경로는 여기 규칙을 더해야 테스트가 통과한다. 예외는 OpenAPI 문서 경로와 관리 포트의 헬스다.
 *
 * 폐기 조회 실패 때 닫는 경로(auth.revocation-check.fail-closed-paths)는 설정에 있다 — application.yml.example.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityFilterChainSupport support) throws Exception {
        return support.build(http, authorize -> authorize
                // 서비스 간 내부 API. 호출자(order · draw)가 사용자 토큰을 Authorization: Bearer 로 그대로 싣는다 —
                // 여기서는 토큰만 검증하고, 대상의 주인 · 결제할 수 있는 상태인지는 호출자가 확인한다.
                .requestMatchers("/internal/**").authenticated()
                .requestMatchers(EndpointRequest.to("health")).permitAll()                          // 헬스 체크(관리 포트의 readiness · liveness)
                .requestMatchers(HttpMethod.GET, OpenApiPaths.docs()).permitAll()                   // OpenAPI 문서
                .anyRequest().denyAll());
    }
}
