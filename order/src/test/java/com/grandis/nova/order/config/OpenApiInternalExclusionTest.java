package com.grandis.nova.order.config;

import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.order.client.preorder.PreorderClient;
import com.grandis.nova.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.Nested;
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

/** /internal/** 은 기본으로 문서에서 빠지고 내부 전용 스키마도 따라 빠진다. 컨텍스트 설정은 OpenApiServerTest 와 같다. */
@OrderIntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true"
})
class OpenApiInternalExclusionTest {

    @Value("${local.server.port}")
    int port;

    @MockitoBean
    RevocationChecker revocationChecker;

    @MockitoBean
    PreorderClient preorderClient;

    @Test
    void internalApiIsLeftOutOfTheDocument() throws Exception {
        JsonNode doc = fetchDocument(port);

        assertThat(doc.path("paths").propertyNames()).noneMatch(OpenApiInternalExclusionTest::isInternal);
        assertThat(doc.path("components").path("schemas").propertyNames())
                .doesNotContain("CancelabilityResponse", "ApiResponseCancelabilityResponse");   // 응답 본체와 그 봉투
        assertThat(doc.path("paths").has("/api/v1/orders/{orderId}")).isTrue();
    }

    @Nested
    @TestPropertySource(properties = "nova.openapi.include-internal=true")
    class IncludeInternalOnRequest {

        @Value("${local.server.port}")
        int port;

        @Test
        void internalApiIsDocumented() throws Exception {
            JsonNode doc = fetchDocument(port);

            assertThat(doc.path("paths").has("/internal/orders/by-preorder/{preorderInternalId}/cancelability")).isTrue();
            assertThat(doc.path("components").path("schemas").has("ApiResponseCancelabilityResponse")).isTrue();
        }
    }

    private static boolean isInternal(String path) {
        return path.equals("/internal") || path.startsWith("/internal/");
    }

    private static JsonNode fetchDocument(int port) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JsonMapper.builder().build().readTree(response.body());
    }
}
