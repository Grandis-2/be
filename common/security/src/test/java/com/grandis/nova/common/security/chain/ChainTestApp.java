package com.grandis.nova.common.security.chain;

import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.security.SecurityFilterChainSupport;
import com.grandis.nova.common.web.ApiResponse;
import java.util.Map;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 체인 테스트용 최소 앱. SecurityConfig 는 서비스에 복사될 예시 그대로다 — 이 규칙이 각 서비스에 복사될 것이다.
 * 공개 8개(api-spec): 상품 GET 4 · 배송 차수 안내 · /auth/kakao/* 2 · /admin/session · /session/refresh(쿠키로 식별). 여기서는 대표만 둔다.
 * 컨트롤러는 조회 실패 시 닫는 경로 5개·공개 경로·관리자 경로·@CurrentCustomerId 경로·본문 있는 경로를 하나씩 대표한다.
 */
@SpringBootApplication(scanBasePackages = "com.grandis.nova")
public class ChainTestApp {

    @Configuration(proxyBeanMethods = false)
    static class SecurityConfig {
        @Bean
        SecurityFilterChain chain(HttpSecurity http, SecurityFilterChainSupport support) throws Exception {
            return support.build(http, a -> a
                    .requestMatchers("/health/**").permitAll()                                        // F-S-02
                    .requestMatchers("/api/v1/auth/**", "/api/v1/admin/session").permitAll()          // F-X-01 로그인
                    .requestMatchers(HttpMethod.POST, "/api/v1/session/refresh").permitAll()          // F-X-01 재발급: 쿠키로 식별. 폐기 검사는 TokenService.rotate
                    .requestMatchers(HttpMethod.GET, "/api/v1/products/**").permitAll()                // F-U-05 상품, F-U-04 배송 차수 안내
                    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                    .anyRequest().authenticated());
        }
    }

    record IntakeRequest(String productId, String variantId) {
    }

    @RestController
    static class Endpoints {
        @GetMapping("/api/v1/products/1")
        ApiResponse<Map<String, String>> product() {
            return ApiResponse.ok(Map.of("name", "phone"));
        }

        @GetMapping("/api/v1/me")
        ApiResponse<Map<String, Long>> me(@CurrentCustomerId Long customerId) {
            return ApiResponse.ok(Map.of("customerId", customerId));
        }

        @GetMapping("/api/v1/admin/stats")
        ApiResponse<Map<String, String>> adminStats() {
            return ApiResponse.ok(Map.of("scope", "admin"));
        }

        @PostMapping("/api/v1/admin/session")
        ApiResponse<Map<String, String>> adminLogin() {
            return ApiResponse.ok(Map.of("role", "ADMIN"));
        }

        @PostMapping("/api/v1/session/refresh")
        ApiResponse<Map<String, String>> refresh() {
            return ApiResponse.ok(Map.of("ok", "refresh"));
        }

        @PostMapping("/api/v1/reservations")
        ApiResponse<Map<String, String>> intake(@RequestBody(required = false) IntakeRequest body) {
            return ApiResponse.ok(Map.of("ok", "intake"));
        }

        @PostMapping("/api/v1/reservations/{id}/cancel")
        ApiResponse<Map<String, String>> cancelReservation(@PathVariable String id) {
            return ApiResponse.ok(Map.of("ok", "cancel"));
        }

        @PostMapping("/api/v1/orders/{orderId}/cancel")
        ApiResponse<Map<String, String>> cancelOrder(@PathVariable String orderId) {
            return ApiResponse.ok(Map.of("ok", "cancel"));
        }

        @PostMapping("/api/v1/orders/{orderId}/payment-attempts")
        ApiResponse<Map<String, String>> paymentInit(@PathVariable String orderId) {
            return ApiResponse.ok(Map.of("ok", "init"));
        }

        @PostMapping("/api/v1/orders/{orderId}/payment-attempts/{tx}/confirm")
        ApiResponse<Map<String, String>> confirm(@PathVariable String orderId, @PathVariable String tx) {
            return ApiResponse.ok(Map.of("ok", "confirm"));
        }
    }
}
