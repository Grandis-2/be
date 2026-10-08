package com.grandis.nova.preorder.config;

import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 문서를 켜면 서비스 이름 그룹(/v3/api-docs/preorder) 하나로 토큰 없이 열린다. 서비스 간 API 는 싣지 않는다. */
@PreorderIntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true"
})
class ApiDocsEnabledTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 문서는_Bearer_토큰으로_인증하고_인증_주체는_파라미터로_노출하지_않는다() throws Exception {
        mockMvc.perform(get("/v3/api-docs/preorder"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/v1/preorders'].get.parameters[*].name",
                        not(hasItem("customerId"))))
                .andExpect(jsonPath("$.paths['/api/v1/preorders/{preorderId}'].get.parameters[*].name",
                        not(hasItem("viewer"))))
                .andExpect(jsonPath("$.paths['/api/v1/preorders/products/{productId}/shipment-batches'].get.security",
                        empty()));
    }

    @Test
    void 인증이_필요한_요청에는_401_403_예시와_공통_실패_봉투가_붙는다() throws Exception {
        mockMvc.perform(get("/v3/api-docs/preorder"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/preorders'].get.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/preorders'].get.responses['403']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/preorders'].get.responses['default']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/preorders/products/{productId}/shipment-batches'].get.responses['401']")
                        .doesNotExist());
    }

    @Test
    void 서비스_간_API_는_문서에_없다() throws Exception {
        mockMvc.perform(get("/v3/api-docs/preorder"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/internal/preorders/{preorderId}/payability']").doesNotExist());
    }

    @Test
    void Swagger_UI_는_토큰_없이_열린다() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
