package com.grandis.nova.preorder.config;

import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.security.CurrentCustomerId;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * preorder 전용 문서 보강. 스킴 · 그룹(/v3/api-docs/preorder) · 실패 봉투 · 켜고 끄기는 common:web 이 맡는다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("springdoc.api-docs.enabled")
class OpenApiConfig {

    static {
        // 인증 주체는 토큰에서 채운다. common:security 의 애너테이션이라 여기서 문서 파라미터에서 뺀다
        SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentCustomerId.class);
    }

    /** 인증이 필요한 요청 전부에 401 · 403 과 오류 봉투 예시를 붙인다. 공개 경로(보안 요구가 빈 것)는 제외. */
    @Bean
    GlobalOpenApiCustomizer authFailureResponses() {
        return openApi -> openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
            if (operation.getSecurity() != null && operation.getSecurity().isEmpty()) {
                return;
            }
            operation.getResponses()
                    .addApiResponse("401", failure(CommonErrorCode.UNAUTHENTICATED,
                            "토큰이 없거나 만료 · 서명 불일치 · 폐기됨"))
                    .addApiResponse("403", failure(CommonErrorCode.FORBIDDEN,
                            "권한 없음(관리자 경로에 회원 토큰, 남의 예약)"));
        }));
    }

    private static ApiResponse failure(CommonErrorCode code, String description) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code.name());
        error.put("message", code.defaultMessage());
        error.put("details", null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("data", null);
        body.put("error", error);
        body.put("timestamp", "2026-10-01T01:00:00Z");
        body.put("traceId", "0b6f3c9e-2d41-4c8a-9f7e-5a1d2c3b4e5f");
        return new ApiResponse().description(description).content(new Content().addMediaType("application/json",
                new MediaType().addExamples(code.name(), new Example().value(body))));
    }
}
