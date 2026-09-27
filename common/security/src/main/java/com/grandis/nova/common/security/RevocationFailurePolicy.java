package com.grandis.nova.common.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

/**
 * 폐기 조회 실패 정책의 "이 요청은 닫는 경로인가" 와 "열린 채 통과한 횟수" 를 한곳에 둔다.
 * 카운터는 CloudWatch 경보의 재료다(auth.revocation.fallback_open). 액추에이터 바인딩은 nova 로 옮길 때 붙인다 — 여기서는 숫자만 센다.
 */
@Component
public class RevocationFailurePolicy {

    private final List<RequestMatcher> failClosed;
    private final AtomicLong fallbackOpenCount = new AtomicLong();

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RevocationFailurePolicy.class);

    public RevocationFailurePolicy(RevocationCheckProperties properties) {
        PathPatternRequestMatcher.Builder builder = PathPatternRequestMatcher.withDefaults();
        this.failClosed = properties.failClosedPaths().stream().map(builder::matcher).map(m -> (RequestMatcher) m).toList();
        // 설정으로 덮을 수 있는 목록이라 실효값을 기동 로그에 남긴다. 오타 하나가 닫는 경로를 조용히 줄인다.
        log.info("auth.revocation-check.fail-closed-paths = {}", properties.failClosedPaths());
    }

    /** true 면 폐기 조회 실패를 401 로 낸다. false 면 통과시키고 기록한다. */
    public boolean failClosed(HttpServletRequest request) {
        return failClosed.stream().anyMatch(m -> m.matches(request));
    }

    public long recordFallbackOpen() {
        return fallbackOpenCount.incrementAndGet();
    }

    public long fallbackOpenCount() {
        return fallbackOpenCount.get();
    }
}
