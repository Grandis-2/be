package com.grandis.nova.member.auth.api;

import com.grandis.nova.common.BusinessException;
import com.grandis.nova.common.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 재발급(`POST /session/refresh`)은 쿠키만으로 동작하는 유일한 상태 변경 경로다. SameSite=Strict 는 다른 사이트의 요청을 막지만
 * **같은 사이트의 형제 서브도메인은 same-site 라 못 막는다**. 그래서 브라우저가 붙이는 `Origin` 헤더를 허용 목록과 대조한다 —
 * 서브도메인이 뚫려도 그 오리진은 목록에 없다. `Origin` 이 없는 요청(비브라우저·구형 클라이언트)도 거절한다: 이 엔드포인트는 브라우저의
 * fetch/XHR 만 부른다는 계약이다(api-spec F-X-01). 설정 `auth.refresh.allowed-origins`(scheme://host[:port], 정확 일치).
 */
@Component
public class RefreshOriginPolicy {

    private static final Logger log = LoggerFactory.getLogger(RefreshOriginPolicy.class);

    @Validated
    @ConfigurationProperties("auth.refresh")
    public record Settings(@NotEmpty List<String> allowedOrigins) {
    }

    private final Settings settings;

    public RefreshOriginPolicy(Settings settings) {
        this.settings = settings;
    }

    /** 허용 목록 밖이거나 없으면 403 FORBIDDEN. 값은 로그에 싣지 않는다(클라이언트가 고른 문자열). */
    public void require(HttpServletRequest request) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null || !settings.allowedOrigins().contains(origin)) {
            log.warn("refresh rejected: origin {} (length={})", origin == null ? "missing" : "not allowed", origin == null ? 0 : origin.length());
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
