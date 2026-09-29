package com.grandis.nova.order.config;

import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.order.client.preorder.PreorderClient;
import com.grandis.nova.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 켜지 않으면 문서가 없다 — 보안 체인이 문서 경로를 열어 두므로 닫는 건 OpenApiExposure 하나다. 컨텍스트 설정은 SecurityRulesTest 와 같다. */
@OrderIntegrationTest
@AutoConfigureMockMvc
class OpenApiOffByDefaultTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    RevocationChecker revocationChecker;

    @MockitoBean
    PreorderClient preorderClient;

    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/swagger-config",
            "/swagger-ui.html", "/swagger-ui/index.html"})
    void docsAreAbsentUnlessEnabled(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    /** 부트의 webjar 핸들러는 문서 켜기와 무관하다 — 체인이 막는다. */
    @Test
    void swaggerUiWebjarIsNotOpenedByTheDocsRule() throws Exception {
        mockMvc.perform(get("/webjars/swagger-ui/index.html")).andExpect(status().isUnauthorized());
    }

    /** springdoc 가 그대로 두면 켬으로 읽는 값이다. */
    @Nested
    @TestPropertySource(properties = {
            "springdoc.api-docs.enabled=no",
            "springdoc.swagger-ui.enabled=",
    })
    class OddValues {

        @ParameterizedTest
        @ValueSource(strings = {"/v3/api-docs", "/swagger-ui/index.html"})
        void stillClosed(String path) throws Exception {
            mockMvc.perform(get(path))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        }
    }
}
