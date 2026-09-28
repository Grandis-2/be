package com.grandis.nova.preorder.config;

import com.grandis.nova.common.CommonErrorCode;
import com.grandis.nova.common.security.CurrentCustomerId;
import com.grandis.nova.common.security.JwtAuthenticationFilter;
import com.grandis.nova.preorder.web.CurrentViewer;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API 문서(dev 프로파일에서만 켜진다). 인증은 세션 토큰 헤더 하나로, Swagger UI 의 Authorize 에 토큰을 넣고 호출한다.
 * 문서는 사용자 · 관리자 · 서비스 간 셋으로 나눈다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("springdoc.api-docs.enabled")
class OpenApiConfig {

    private static final String SESSION_TOKEN = "sessionToken";

    static {
        // 인증 주체는 토큰에서 채운다. 요청 파라미터로 문서에 나오지 않게 한다
        SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentCustomerId.class, CurrentViewer.class);
    }

    @Bean
    OpenAPI preorderOpenApi() {
        return new OpenAPI()
                .info(new Info().title("preorder API").version("v1")
                        .description("사전예약 접수 · 조회 · 취소, 모집 일정 관리, 동기화 작업 재처리"))
                .components(new Components().addSecuritySchemes(SESSION_TOKEN, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(JwtAuthenticationFilter.HEADER)
                        .description("member 가 발급한 액세스 토큰 원문(Bearer 접두 없음)")))
                .addSecurityItem(new SecurityRequirement().addList(SESSION_TOKEN));
    }

    @Bean
    GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder().group("public").displayName("사용자")
                .pathsToMatch("/api/v1/**").pathsToExclude("/api/v1/admin/**").build();
    }

    @Bean
    GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder().group("admin").displayName("관리자").pathsToMatch("/api/v1/admin/**").build();
    }

    @Bean
    GroupedOpenApi internalApi() {
        return GroupedOpenApi.builder().group("internal").displayName("서비스 간").pathsToMatch("/internal/**").build();
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
