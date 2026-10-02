package com.grandis.nova.member.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.member.support.MemberIntegrationTest;
import jakarta.servlet.Filter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * member 의 OpenAPI 문서(켠 상태). 공통 설정(common:web OpenApiConfiguration)이 문서 전체에 Bearer 를 걸므로, 토큰 없이 부르는 경로는
 * 컨트롤러가 @SecurityRequirements 로 빼야 한다 — 그 목록이 실제 보안 체인(MemberSecurityConfig)이 익명에게 여는 연산과 같은지 본다.
 * member 는 문서 허브라 Swagger UI 드롭다운도 여기서 본다.
 */
@MemberIntegrationTest
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true",
        "spring.application.name=member",
        "nova.openapi.hub-services=member,catalog,preorder,order"
})
@DisplayName("member OpenAPI 문서 — 공개 경로(체인과 대조) · 로그아웃 204 · 토큰 인자 · 실패 봉투 · 허브")
class MemberOpenApiDocumentTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy springSecurityFilterChain;

    MockMvc mockMvc;
    JsonNode doc;

    @BeforeEach
    void fetch() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
        doc = json("/v3/api-docs/member");
    }

    @Test
    @DisplayName("문서에서 Bearer 가 빠진 연산은 보안 체인이 익명에게 여는 연산과 정확히 같다 — 기대 목록을 손으로 적지 않고 실제 체인에서 뽑는다")
    void publicOperationsMatchTheSecurityChain() {
        assertThat(doc.path("security").findValues("bearerAuth")).as("문서 전체 Bearer").hasSize(1);
        List<String> documentedOpen = new ArrayList<>();
        List<String> chainOpen = new ArrayList<>();
        operations().forEach((name, operation) -> {
            if (operation.has("security") && operation.path("security").isEmpty()) {
                documentedOpen.add(name);
            }
            if (anonymousIsGranted(name)) {
                chainOpen.add(name);
            }
        });
        // 익명 요청을 보내 401 이 아닌지로 가르면 안 된다 — 쿠키 없는 재발급은 공개 경로인데도 컨트롤러가 401 을 낸다
        assertThat(documentedOpen).containsExactlyInAnyOrderElementsOf(chainOpen);
        assertThat(chainOpen).as("대조 — 체인이 여는 연산이 실제로 있다").contains("POST /api/v1/session/refresh", "GET /.well-known/jwks.json");
    }

    @Test
    @DisplayName("로그아웃 · 관리자 세션 전부 폐기의 성공은 204 다(api-spec). ResponseEntity 로 돌려주면 문서가 200 으로 추론한다")
    void logoutSuccessIs204() {
        for (String name : List.of("DELETE /api/v1/session", "DELETE /api/v1/admin/sessions")) {
            JsonNode responses = operations().get(name).path("responses");
            assertThat(responses.has("204")).as(name).isTrue();
            assertThat(responses.has("200")).as(name).isFalse();
        }
    }

    /** 실제 보안 체인의 인가 규칙에 익명 인증으로 물어본다. 경로 변수는 1 로 채운다. */
    private boolean anonymousIsGranted(String operation) {
        String[] parts = operation.split(" ", 2);
        String path = parts[1].replaceAll("\\{[^}]+}", "1");
        MockHttpServletRequest request = new MockHttpServletRequest(parts[0], path);
        request.setServletPath(path);
        Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        for (SecurityFilterChain chain : springSecurityFilterChain.getFilterChains()) {
            if (!chain.matches(request)) {
                continue;
            }
            for (Filter filter : chain.getFilters()) {
                if (filter instanceof AuthorizationFilter authorization) {
                    AuthorizationResult result = authorization.getAuthorizationManager().authorize(() -> anonymous, request);
                    return result != null && result.isGranted();
                }
            }
        }
        throw new AssertionError("인가 필터를 찾지 못했다: " + operation);
    }

    @Test
    @DisplayName("토큰에서 오는 값(회원 id · 인증 주체)은 요청 인자로 나오지 않는다")
    void tokenDerivedArgumentsAreNotRequestParameters() {
        List<String> leaked = new ArrayList<>();
        operations().forEach((name, operation) -> operation.path("parameters").forEach(parameter -> {
            String param = parameter.path("name").asString();
            if (param.equals("customerId") || param.equals("principal")) {
                leaked.add(name + " " + param);
            }
        }));
        assertThat(leaked).isEmpty();
        assertThat(operations()).as("내 정보 연산이 문서에 있다 — 빈 문서로 통과하지 않게").containsKey("GET /api/v1/me/profile");
    }

    @Test
    @DisplayName("모든 연산에 실패 봉투(default 응답)가 붙는다")
    void everyOperationDocumentsTheFailureEnvelope() {
        List<String> missing = new ArrayList<>();
        operations().forEach((name, operation) -> {
            String ref = operation.path("responses").path("default").path("content")
                    .path("application/json").path("schema").path("$ref").asString();
            if (!ref.equals("#/components/schemas/ApiResponseVoid")) {
                missing.add(name);
            }
        });
        assertThat(missing).isEmpty();
    }

    @Test
    @DisplayName("허브 — Swagger UI 드롭다운에 네 서비스 문서가 나오고 첫 화면은 member 다")
    void hubListsEveryServiceGroup() throws Exception {
        JsonNode config = json("/v3/api-docs/swagger-config");
        assertThat(config.path("urls").findValuesAsString("url")).containsExactlyInAnyOrder(
                "/v3/api-docs/member", "/v3/api-docs/catalog", "/v3/api-docs/preorder", "/v3/api-docs/order");
        assertThat(config.path("urls.primaryName").asString()).isEqualTo("member");
    }

    private JsonNode json(String path) throws Exception {
        String body = mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }

    private Map<String, JsonNode> operations() {
        Map<String, JsonNode> operations = new LinkedHashMap<>();
        doc.path("paths").properties().forEach(path -> path.getValue().properties()
                .forEach(operation -> operations.put(operation.getKey().toUpperCase() + " " + path.getKey(), operation.getValue())));
        assertThat(operations).as("문서에 연산이 있다 — 빈 문서로 단언이 통과하지 않게").isNotEmpty();
        return operations;
    }
}
