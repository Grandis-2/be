package com.grandis.nova.order.config;

import com.grandis.nova.common.security.BearerTokens;
import com.grandis.nova.common.security.JwtTokenProvider;
import com.grandis.nova.common.security.RevocationChecker;
import com.grandis.nova.common.security.Role;
import com.grandis.nova.common.security.TokenType;
import com.grandis.nova.order.client.preorder.PreorderClient;
import com.grandis.nova.order.support.OrderIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static com.grandis.nova.order.config.OpenApiHttp.get;
import static com.grandis.nova.order.config.OpenApiHttp.getJson;
import static com.grandis.nova.order.config.OpenApiHttp.json;
import static com.grandis.nova.order.config.OpenApiHttp.request;
import static com.grandis.nova.order.config.OpenApiHttp.send;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 내장 톰캣을 띄워 HTTP 로 확인한다 — MockMvc 는 정적 리소스 서빙 · 메시지 컨버터 등록을 그대로 재현하지 않는다.
 * springdoc 가 Jackson 2 모듈을 끌고 와도 응답 직렬화(ISO 시각 · null 필드 유지)가 그대로인지도 본다.
 */
@OrderIntegrationTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true",
        "spring.application.name=order"
})
class OpenApiServerTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JwtTokenProvider tokens;

    @MockitoBean
    RevocationChecker revocationChecker;   // Redis 없음

    @MockitoBean
    PreorderClient preorderClient;

    @Test
    void apiDocsServeOrderPathsAnonymously() throws Exception {
        HttpResponse<String> response = get(port, "/v3/api-docs");
        JsonNode doc = json(response);

        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        assertThat(doc.path("openapi").asString()).startsWith("3.");
        assertThat(doc.path("paths").has("/api/v1/orders/{orderId}")).isTrue();
    }

    @Test
    void yamlVariantAndUiConfigAreOpen() throws Exception {
        for (String path : new String[]{"/v3/api-docs.yaml", "/v3/api-docs.yaml/order"}) {
            HttpResponse<String> yaml = get(port, path);
            assertThat(yaml.statusCode()).as(path).isEqualTo(200);
            assertThat(yaml.body()).as(path).startsWith("openapi:");
        }
        assertThat(get(port, "/v3/api-docs/swagger-config").statusCode()).isEqualTo(200);
    }

    @Test
    void swaggerUiIsServedFromWebjar() throws Exception {
        HttpResponse<String> index = get(port, "/swagger-ui/index.html");
        assertThat(index.statusCode()).isEqualTo(200);
        assertThat(index.body()).contains("swagger-ui");

        HttpResponse<String> entry = get(port, "/swagger-ui.html");
        assertThat(entry.statusCode()).isEqualTo(302);
        assertThat(entry.headers().firstValue("Location")).hasValueSatisfying(
                location -> assertThat(location).contains("/swagger-ui/index.html"));
    }

    @Test
    void docsPathsAreOpenForGetOnly() throws Exception {
        HttpResponse<String> response = send(request(port, "/v3/api-docs").POST(HttpRequest.BodyPublishers.noBody()).build());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    /** 컨버터를 거치는 응답이어야 한다 — 401 봉투는 ObjectMapper 로 직접 쓴다. 주문이 없는 예약이라 orderStatus · reason 이 null 이다. */
    @Test
    void responseSerializationStaysJackson3Shaped() throws Exception {
        String user = tokens.create("101", Role.USER, UUID.randomUUID(), TokenType.ACCESS);
        JsonNode body = json(send(request(port, "/internal/orders/by-preorder/987654321/cancelability")
                .header(BearerTokens.HEADER, BearerTokens.value(user)).GET().build()));

        assertThat(body.path("timestamp").isString()).isTrue();
        assertThat(body.path("timestamp").asString()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
        assertThat(body.has("error")).isTrue();
        assertThat(body.path("error").isNull()).isTrue();
        assertThat(body.path("data").has("orderStatus")).isTrue();
        assertThat(body.path("data").path("orderStatus").isNull()).isTrue();
        assertThat(body.path("data").path("reason").isNull()).isTrue();
    }

    /** 스키마는 swagger-core 가 Jackson 2 로 만든다. @JsonUnwrapped 가 반영돼 실제 JSON 처럼 평평해야 한다. */
    @Test
    void unwrappedDtoSchemaIsFlatLikeTheRealJson() throws Exception {
        JsonNode detail = getJson(port, "/v3/api-docs").path("components").path("schemas")
                .path("OrderDetailResponse").path("properties");

        assertThat(detail.has("orderId")).isTrue();
        assertThat(detail.has("events")).isTrue();
        assertThat(detail.has("order")).isFalse();
    }
}
