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
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;

import java.util.List;

/**
 * HTTP 서비스 공통 OpenAPI 설정.
 *
 * - 인증: Bearer(JWT)를 문서 전체에 건다. 공개 경로는 컨트롤러가 @SecurityRequirements() 로 뺀다.
 * - 실패 봉투: GlobalExceptionHandler 에 @ResponseStatus 가 없어 springdoc 가 오류 응답을 만들지 않으므로 모든 연산에
 *   ApiResponse&lt;Void&gt; 를 default 응답으로 건다. 성공 봉투는 제네릭이라 springdoc 가 data 타입별로 만든다.
 * - 경로: /v3/api-docs 와 함께 서비스 이름 그룹(/v3/api-docs/{spring.application.name})으로도 낸다. 허브({@link OpenApiHub})가 쓴다.
 * - 서버 주소: "/" 로 고정한다. springdoc 기본값은 요청받은 서비스 주소라 Try it out 이 프록시 · ALB 를 우회한다.
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
                .addSecurityItem(new SecurityRequirement().addList(BEARER))
                .servers(List.of(new Server().url("/")));
    }

    @Bean
    @ConditionalOnProperty("spring.application.name")
    GroupedOpenApi serviceGroup(@Value("${spring.application.name}") String service) {
        return GroupedOpenApi.builder().group(service).pathsToMatch("/**").build();
    }

    /** 그룹 문서에도 걸리게 전역 커스터마이저로 둔다. 스키마는 ApiResponse&lt;Void&gt; 에서 만들고, 이미 있는 default 는 덮지 않는다. */
    @Bean
    GlobalOpenApiCustomizer errorEnvelopeResponse() {
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
