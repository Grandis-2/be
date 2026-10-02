package com.grandis.nova.member.openapi;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.member.support.MemberIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 켜지 않으면 문서가 없다 — 보안 체인이 문서 경로를 열어 두므로(MemberSecurityConfig) 닫는 건 OpenApiExposure 하나다.
 * 허브 설정이 있어도 마찬가지다. 운영과 같이 서비스 이름을 줘서 그룹 문서 경로까지 본다.
 */
@MemberIntegrationTest
@TestPropertySource(properties = {
        "spring.application.name=member",
        "nova.openapi.hub-services=member,catalog,preorder,order"
})
@DisplayName("member OpenAPI 문서 — 기본은 꺼져 있다")
class MemberOpenApiOffByDefaultTest {

    @Autowired WebApplicationContext context;
    @Autowired FilterChainProxy springSecurityFilterChain;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/member", "/v3/api-docs/swagger-config", "/swagger-ui.html", "/swagger-ui/index.html"})
    @DisplayName("문서 · UI 설정 · 그룹 문서가 없다(404)")
    void docsAreAbsentUnlessEnabled(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isNotFound());
    }
}
