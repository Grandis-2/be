package com.grandis.nova.common.security;

import tools.jackson.databind.ObjectMapper;   // Boot 4 = Jackson 3 (tools.jackson)
import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * 401 과 403 을 팀 공통 봉투(nova common:web ApiResponse)로 쓴다. entry point 와 access denied handler 가 같은 본문 작성 함수를 쓰도록 한 클래스에 둔다.
 * 401 UNAUTHENTICATED("세션 없음·만료"), 403 FORBIDDEN("권한 부족"). 이유(필터의 ATTR_FAILURE_REASON)는 응답에 내지 않는다.
 * 폐기 조회 실패로 닫힌 401 만 `error.details.retryable = true` 다(폐기 조회 실패 정책). 공통 봉투에는 retryable 칸이 없으므로(nova PR #4, 2026-09-22)
 * 로그인 쪽 의미는 details 에 싣는다 — 프론트는 이 값으로 "잠시 후 재시도" 를 가른다.
 */
public final class JsonAuthFailureHandlers {

    /** 닫힌 경로에서 폐기 조회가 안 돼 거절했을 때의 details. 같은 요청을 잠시 뒤 다시 보내면 성공할 수 있다는 뜻. */
    public static final Map<String, Object> RETRYABLE_DETAILS = Map.of("retryable", true);

    private final ObjectMapper objectMapper;

    public JsonAuthFailureHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AuthenticationEntryPoint entryPoint() {
        return (request, response, ex) -> {
            boolean retryable = Boolean.TRUE.equals(request.getAttribute(JwtAuthenticationFilter.ATTR_RETRYABLE));
            write(response, 401, ApiResponse.fail(CommonErrorCode.UNAUTHENTICATED,
                    CommonErrorCode.UNAUTHENTICATED.defaultMessage(), retryable ? RETRYABLE_DETAILS : null));
        };
    }

    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) -> write(response, 403,
                ApiResponse.fail(CommonErrorCode.FORBIDDEN, CommonErrorCode.FORBIDDEN.defaultMessage()));
    }

    private void write(HttpServletResponse response, int status, ApiResponse<Void> body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
