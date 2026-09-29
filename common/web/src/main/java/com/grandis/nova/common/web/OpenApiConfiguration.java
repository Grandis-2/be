package com.grandis.nova.common.web;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;

/**
 * HTTP 서비스 공통 OpenAPI 설정: Bearer(JWT) 인증 스킴과 실패 봉투.
 *
 * 성공 봉투는 정의하지 않는다 — ApiResponse&lt;T&gt; 가 제네릭이라 springdoc 가 엔드포인트마다 data 타입으로 만든다.
 * 실패는 상태 코드와 무관하게 ApiResponse&lt;Void&gt; 인데 GlobalExceptionHandler 에 @ResponseStatus 가 없어
 * springdoc 가 오류 응답을 만들지 않는다. 그래서 모든 연산에 default 응답으로 건다.
 * 공개 경로는 컨트롤러가 @SecurityRequirements() 로 뺀다.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    static final String BEARER = "bearerAuth";

    @Bean
    OpenAPI novaOpenApi(@Value("${spring.application.name:nova}") String service) {
        return new OpenAPI()
                .info(new Info().title(service + " API").version("v1"))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    /** 스키마는 ApiResponse&lt;Void&gt; 에서 만든다 — 필드를 손으로 베끼면 봉투가 바뀔 때 어긋난다. 이미 있는 default 는 덮지 않는다. */
    @Bean
    OpenApiCustomizer errorEnvelopeResponse() {
        return openApi -> {
            boolean v31 = openApi.getSpecVersion() == SpecVersion.V31;
            ResolvedSchema failure = ModelConverters.getInstance(v31).readAllAsResolvedSchema(
                    ResolvableType.forClassWithGenerics(ApiResponse.class, Void.class).getType());
            Components components = openApi.getComponents() == null ? new Components() : openApi.getComponents();
            openApi.components(components);
            failure.referencedSchemas.forEach(components::addSchemas);

            String name = failure.schema.getName() != null ? failure.schema.getName() : "ApiResponseVoid";
            components.addSchemas(name, failure.schema);
            Content content = new Content().addMediaType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                    new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + name)));

            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                ApiResponses responses = operation.getResponses() == null ? new ApiResponses() : operation.getResponses();
                operation.responses(responses);
                if (!responses.containsKey("default")) {
                    responses.addApiResponse("default", new io.swagger.v3.oas.models.responses.ApiResponse()
                            .description("실패. 상태 코드와 무관하게 같은 봉투이며 error.code 로 분기한다")
                            .content(content));
                }
            }));
        };
    }
}
