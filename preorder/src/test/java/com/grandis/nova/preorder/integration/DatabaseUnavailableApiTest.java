package com.grandis.nova.preorder.integration;

import com.grandis.nova.preorder.integration.catalog.CatalogClient;
import com.grandis.nova.preorder.support.PreorderIntegrationTest;
import com.grandis.nova.preorder.support.ShopFixtures;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.grandis.nova.preorder.support.AccessTokens.admin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 커넥션 풀이 바닥나면 500 이 아니라 503 + Retry-After 다(다시 보내면 되는 일시 장애). 커넥션 대기 상한(2초)만큼 걸린다. */
@PreorderIntegrationTest
@AutoConfigureMockMvc
class DatabaseUnavailableApiTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    HikariDataSource dataSource;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @MockitoBean
    CatalogClient catalogClient;

    @Test
    void 커넥션_풀이_바닥나면_503_과_Retry_After() throws Exception {
        UUID productId = new ShopFixtures(jdbcTemplate).openPreorderProduct().productId();
        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < dataSource.getMaximumPoolSize(); i++) {
                held.add(dataSource.getConnection());
            }

            mockMvc.perform(get("/api/v1/admin/preorders/products/{id}/campaign", productId).with(admin()))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                    .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"));
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }
    }
}
