package com.grandis.nova.common.security;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 폐기 조회가 실패했을 때 401 로 닫는 경로 목록. 목록 밖은 전부 열린다(통과 + 경고 + 카운터).
 * 기본값은 아래 여섯 개다. 설정으로 덮어쓸 수 있지만, **빈 목록은 기본값으로 대체한다**(보안 검토에서 잡힌 것: null 만 대체하고 빈 List 는 그대로 써서
 * 전부 fail-open 이 됐었다). 정말 전부 열고 싶다면 이 코드를 고쳐야 한다 — 설정 한 줄로 그렇게 될 수 없게.
 * 패턴은 Spring Security 7 의 PathPattern 문법이다. 메서드는 보지 않는다 — 같은 경로의 GET 도 닫힌다(조회가 닫혀도 손해가 없는 경로들).
 */
@ConfigurationProperties("auth.revocation-check")
public record RevocationCheckProperties(List<String> failClosedPaths) {

    public static final List<String> DEFAULT_FAIL_CLOSED_PATHS = List.of(
            "/api/v1/admin/**",                        // 관리자 기능. POST /api/v1/admin/session 은 공개라 토큰이 없어 여기 안 걸린다
            "/api/v1/session/refresh",                 // 폐기 세션이 새 토큰을 받는 유일한 통로
            "/api/v1/orders/*/payment-attempts/**",    // 돈이 움직인다
            "/api/v1/reservations/*/cancel",           // 되돌릴 수 없다
            "/api/v1/orders/*/cancel",
            "/api/v1/me/**");                          // 개인정보(기본 배송지). 접수 흐름 밖이라 닫아도 잃는 게 없다(2026-09-21 추가)

    private static final Logger log = LoggerFactory.getLogger(RevocationCheckProperties.class);

    public RevocationCheckProperties {
        if (failClosedPaths == null || failClosedPaths.isEmpty()) {
            if (failClosedPaths != null) {
                log.warn("auth.revocation-check.fail-closed-paths is empty; using the default {} paths", DEFAULT_FAIL_CLOSED_PATHS.size());
            }
            failClosedPaths = DEFAULT_FAIL_CLOSED_PATHS;
        }
    }
}
