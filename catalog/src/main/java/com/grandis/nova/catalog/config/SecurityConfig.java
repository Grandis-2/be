package com.grandis.nova.catalog.config;

import com.grandis.nova.common.security.SecurityFilterChainSupport;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 경로별 권한만 정한다. 토큰 검증(`Authorization: Bearer`, JWKS) · 폐기 조회 · 401/403 봉투는 common:security 의 체인이 맡는다(NV-139 채택).
 * 폐기 조회가 실패했을 때 닫는 경로는 공통 기본값(`/api/v1/admin/**` · 재발급 · 내 정보)에 리뷰 쓰기(작성 · 수정 · 삭제)를 더한다 — CatalogDefaults.
 *
 * 공개 조회는 **명시한 GET 만** 열고 나머지는 거부한다(preorder 와 같은 규약). anyRequest().permitAll() 이면 /admin · /internal 밖에 새로 생기는
 * 엔드포인트가 기본으로 공개되는데 그걸 잡는 시험이 없다 — 공개로 열 엔드포인트는 여기 목록에 더한다.
 * 공개 조회는 토큰을 보지 않는다 — 관리자 미리보기는 관리자 상세(/api/v1/admin/products/{id})로 옮겼다(2026-09-29).
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityFilterChainSupport support) throws Exception {
        return support.build(http, authorize -> authorize
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()                                     // 오류 페이지 디스패치
                .requestMatchers(EndpointRequest.to("health")).permitAll()                                    // 헬스 체크(관리 포트의 readiness · liveness)
                .requestMatchers(HttpMethod.GET, "/api/v1/products/**", "/api/v1/categories/**").permitAll()   // 상품 목록 · 상세 · 옵션 상세 · 상품 리뷰 · 카테고리 트리
                .requestMatchers(HttpMethod.GET, "/api/v1/reviews/mine").hasRole("USER")                        // 내 리뷰
                .requestMatchers(HttpMethod.GET, "/api/v1/reviews").permitAll()                                 // 구매후기 모아보기
                .requestMatchers("/api/v1/reviews", "/api/v1/reviews/*").hasRole("USER")                        // 리뷰 작성 · 수정 · 삭제 — 위 두 GET 규칙보다 뒤에 둔다
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                // 내부 조회는 접수(USER)와 관리자 등록(ADMIN)이 같이 쓴다. 역할이 둘뿐이라 authenticated() 와 동작은 같지만 계약대로 적는다
                .requestMatchers("/internal/**").hasAnyRole("USER", "ADMIN")
                .anyRequest().denyAll());
    }
}
