package com.grandis.nova.preorder.config;

import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** dev 프로파일에서만 API 문서가 열린다. 토큰 없이 볼 수 있고, 인증은 세션 토큰 헤더 하나로 선언된다. */
@PreorderIntegrationTest
@ActiveProfiles({"test", "dev"})
@AutoConfigureMockMvc
class ApiDocsDevProfileTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 사용자_문서는_세션_토큰_헤더로_인증하고_인증_주체는_파라미터로_노출하지_않는다() throws Exception {
        mockMvc.perform(get("/v3/api-docs/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.sessionToken.in").value("header"))
                .andExpect(jsonPath("$.components.securitySchemes.sessionToken.name").value("X-Session-Token"))
                .andExpect(jsonPath("$.paths['/api/v1/preorders'].get.parameters[*].name",
                        not(hasItem("customerId"))))
                .andExpect(jsonPath("$.paths['/api/v1/preorders'].get.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/products/{productId}/shipment-batches'].get.security",
                        empty()))
                .andExpect(jsonPath("$.paths['/api/v1/admin/preorders']").doesNotExist());
    }

    @Test
    void 관리자와_서비스_간_문서는_따로_나뉜다() throws Exception {
        mockMvc.perform(get("/v3/api-docs/admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/admin/preorders'].get.responses['403']").exists())
                .andExpect(jsonPath("$.paths['/internal/preorders/{preorderId}/payability']").doesNotExist());
        mockMvc.perform(get("/v3/api-docs/internal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/internal/preorders/{preorderId}/payability'].get").exists());
    }

    @Test
    void Swagger_UI_는_토큰_없이_열리고_세_문서를_고를_수_있다() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.urls[*].url", hasItems("/v3/api-docs/public", "/v3/api-docs/admin",
                        "/v3/api-docs/internal")));
    }
}
