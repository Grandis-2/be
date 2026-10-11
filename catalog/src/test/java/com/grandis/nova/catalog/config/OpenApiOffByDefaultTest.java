package com.grandis.nova.catalog.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 켜지 않으면 문서가 없다 — 보안 체인이 문서 경로를 열어 두므로 닫는 건 OpenApiExposure 하나다.
 * 운영과 같이 서비스 이름을 줘서 그룹 문서(/v3/api-docs/catalog)까지 생길 수 있는 상태로 본다.
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.application.name=catalog")
@DisplayName("OpenAPI 문서는 켜지 않으면 없다 — 경로는 열려 있어도 404")
class OpenApiOffByDefaultTest {

    @Autowired
    MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/catalog", "/v3/api-docs.yaml/catalog",
            "/v3/api-docs/swagger-config", "/swagger-ui.html", "/swagger-ui/index.html"})
    @DisplayName("기본값이면 문서 · UI 가 404")
    void docsAreAbsentUnlessEnabled(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("부트의 webjar 경로는 문서 규칙이 열지 않는다 — 401")
    void swaggerUiWebjarIsNotOpenedByTheDocsRule() throws Exception {
        mockMvc.perform(get("/webjars/swagger-ui/index.html")).andExpect(status().isUnauthorized());
    }

    /** springdoc 가 그대로 두면 켬으로 읽는 값이다. */
    @Nested
    @TestPropertySource(properties = {
            "springdoc.api-docs.enabled=no",
            "springdoc.swagger-ui.enabled=",
    })
    @DisplayName("정확히 true 가 아니면 꺼짐")
    class OddValues {

        @ParameterizedTest
        @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs/catalog", "/v3/api-docs.yaml/catalog", "/swagger-ui/index.html"})
        @DisplayName("no · 빈 값도 404")
        void stillClosed(String path) throws Exception {
            mockMvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }
}
