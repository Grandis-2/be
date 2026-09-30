package com.grandis.nova.preorder.config;

import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 문서를 켜지 않으면(기본) API 문서가 없다. */
@PreorderIntegrationTest
@AutoConfigureMockMvc
class ApiDocsDisabledTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 문서와_Swagger_UI_경로는_404() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
        mockMvc.perform(get("/v3/api-docs/preorder")).andExpect(status().isNotFound());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
    }
}
