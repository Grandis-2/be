package com.grandis.nova.order.config;

import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.order.client.preorder.PreorderClient;
import com.grandis.nova.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** common:web OpenApiConfiguration 이 만든 문서. 컨텍스트 설정은 OpenApiServerTest 와 같게 둬 캐시를 공유한다. */
@OrderIntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true"
})
class OpenApiDocumentTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Value("${local.server.port}")
    int port;

    @MockitoBean
    RevocationChecker revocationChecker;

    @MockitoBean
    PreorderClient preorderClient;

    JsonNode doc;

    @BeforeEach
    void fetch() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        doc = JSON.readTree(response.body());
    }

    @Test
    void bearerJwtIsTheDocumentWideScheme() {
        JsonNode scheme = doc.path("components").path("securitySchemes").path("bearerAuth");
        assertThat(scheme.path("type").asString()).isEqualTo("http");
        assertThat(scheme.path("scheme").asString()).isEqualTo("bearer");
        assertThat(scheme.path("bearerFormat").asString()).isEqualTo("JWT");
        assertThat(doc.path("security").findValues("bearerAuth")).hasSize(1);
    }

    @Test
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

        JsonNode envelope = doc.path("components").path("schemas").path("ApiResponseVoid").path("properties");
        assertThat(envelope.propertyNames()).containsExactlyInAnyOrder("success", "data", "error", "timestamp", "traceId");
        assertThat(envelope.path("error").path("$ref").asString()).isEqualTo("#/components/schemas/ApiError");
        assertThat(doc.path("components").path("schemas").path("ApiError").path("properties").propertyNames())
                .containsExactlyInAnyOrder("code", "message", "details");
    }

    /**
     * @CurrentViewer 값이 문서에 나오면 클라이언트가 고르는 값으로 읽힌다.
     * @CurrentCustomerId(common:security)는 아직 숨김이 없어 customerId 가 나온다 — 그쪽에 반영되면 여기 단언에 더한다.
     */
    @Test
    void tokenDerivedArgumentsAreNotRequestParameters() {
        List<String> leaked = new ArrayList<>();
        operations().forEach((name, operation) -> operation.path("parameters").forEach(parameter -> {
            String param = parameter.path("name").asString();
            if (param.equals("viewer")) {
                leaked.add(name + " " + param);
            }
        }));
        assertThat(leaked).isEmpty();
        assertThat(doc.path("components").path("schemas").has("Viewer")).isFalse();
    }

    private Map<String, JsonNode> operations() {
        Map<String, JsonNode> operations = new java.util.LinkedHashMap<>();
        doc.path("paths").properties().forEach(path -> path.getValue().properties().forEach(
                method -> operations.put(method.getKey().toUpperCase() + " " + path.getKey(), method.getValue())));
        assertThat(operations).isNotEmpty();
        return operations;
    }
}
