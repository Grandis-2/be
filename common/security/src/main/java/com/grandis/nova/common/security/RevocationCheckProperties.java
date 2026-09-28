package com.grandis.nova.common.security;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpMethod;

/**
 * 폐기 조회가 실패했을 때 401 로 닫는 경로 목록. 목록 밖은 전부 열린다(통과 + 경고 + 카운터).
 * 기본값은 아래 여섯 개다. 설정으로 덮어쓸 수 있지만, **빈 목록은 기본값으로 대체한다**(보안 검토에서 잡힌 것: null 만 대체하고 빈 List 는 그대로 써서
 * 전부 fail-open 이 됐었다). 정말 전부 열고 싶다면 이 코드를 고쳐야 한다 — 설정 한 줄로 그렇게 될 수 없게.
 *
 * <p>항목은 Spring Security 7 의 PathPattern 이고, 앞에 HTTP 메서드를 붙일 수 있다 — {@code "POST /api/v1/preorders/{id}/cancel"} 꼴(경로 조각은 PathPattern 의 별표. 주석 안이라 풀어 적었다).
 * 메서드가 없으면 그 경로의 모든 메서드가 닫힌다. 메서드를 붙이는 이유는 같은 접두를 쓰는 조회(GET)까지 닫지 않으려는 것이다(2026-09-28, NV-138).
 * 모양이 틀린 항목(모르는 메서드 · 소문자 메서드 · '/' 로 시작하지 않는 경로 · 조각 셋 이상)은 **기동 실패**다 — 오타 하나가 닫는 경로를 조용히 줄이면 안 된다.
 */
@ConfigurationProperties("auth.revocation-check")
public record RevocationCheckProperties(List<String> failClosedPaths) {

    public static final List<String> DEFAULT_FAIL_CLOSED_PATHS = List.of(
            "/api/v1/admin/**",                        // 관리자 기능. POST /api/v1/admin/session 은 공개라 토큰이 없어 여기 안 걸린다
            "/api/v1/session/refresh",                 // 폐기 세션이 새 토큰을 받는 유일한 통로
            "/api/v1/orders/*/payment-attempts/**",    // 돈이 움직인다
            "POST /api/v1/preorders/*/cancel",         // 되돌릴 수 없다. 접수(POST /api/v1/preorders)는 D-2 대로 여는 쪽 — 오픈 스파이크 때 막히면 안 된다
            "POST /api/v1/orders/*/cancel",
            "/api/v1/me/**");                          // 개인정보(기본 배송지). 접수 흐름 밖이라 닫아도 잃는 게 없다(2026-09-21 추가)

    private static final Logger log = LoggerFactory.getLogger(RevocationCheckProperties.class);
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    public RevocationCheckProperties {
        if (failClosedPaths == null || failClosedPaths.isEmpty()) {
            if (failClosedPaths != null) {
                log.warn("auth.revocation-check.fail-closed-paths is empty; using the default {} paths", DEFAULT_FAIL_CLOSED_PATHS.size());
            }
            failClosedPaths = DEFAULT_FAIL_CLOSED_PATHS;
        }
        failClosedPaths.forEach(Entry::parse);   // 모양이 틀리면 여기서 던진다 — 기동 실패
    }

    public List<Entry> entries() {
        return failClosedPaths.stream().map(Entry::parse).toList();
    }

    /** 목록 항목 하나 — 선택적 메서드와 경로 패턴. */
    public record Entry(Optional<HttpMethod> method, String pattern) {

        public static Entry parse(String raw) {
            String text = raw == null ? "" : raw.strip();
            String[] parts = text.split("\\s+");
            if (parts.length == 1 && parts[0].startsWith("/")) {
                return new Entry(Optional.empty(), parts[0]);
            }
            if (parts.length == 2 && METHODS.contains(parts[0]) && parts[1].startsWith("/")) {
                return new Entry(Optional.of(HttpMethod.valueOf(parts[0])), parts[1]);
            }
            throw new IllegalArgumentException("auth.revocation-check.fail-closed-paths entry must be \"/path\" or \"METHOD /path\" "
                    + "(METHOD in " + METHODS + "): \"" + raw + "\"");
        }
    }
}
