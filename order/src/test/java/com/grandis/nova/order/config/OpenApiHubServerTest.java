package com.grandis.nova.order.config;

import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.order.client.preorder.PreorderClient;
import com.grandis.nova.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** 허브 설정이 Swagger UI 드롭다운이 되는지. 드롭다운은 이름순이라 첫 화면(primaryName)을 따로 본다. 동작은 서비스와 무관해 order 로 확인한다. */
@OrderIntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true",
        "spring.application.name=order",
        "nova.openapi.hub-services=member,catalog,preorder,order"
})
class OpenApiHubServerTest {

    @Value("${local.server.port}")
    int port;

    @MockitoBean
    RevocationChecker revocationChecker;

    @MockitoBean
    PreorderClient preorderClient;

    @Test
    void swaggerUiListsEveryServiceGroup() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs/swagger-config")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode config = JsonMapper.builder().build().readTree(response.body());
        JsonNode urls = config.path("urls");

        assertThat(urls.findValuesAsString("name")).containsExactlyInAnyOrder("member", "catalog", "preorder", "order");
        assertThat(urls.findValuesAsString("url")).containsExactlyInAnyOrder(
                "/v3/api-docs/member", "/v3/api-docs/catalog", "/v3/api-docs/preorder", "/v3/api-docs/order");
        assertThat(config.path("urls.primaryName").asString()).isEqualTo("member");
    }
}
