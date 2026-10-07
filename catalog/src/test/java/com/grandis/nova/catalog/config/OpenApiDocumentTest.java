package com.grandis.nova.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.grandis.nova.catalog.category.CategoryNode;
import com.grandis.nova.catalog.detail.ProductDetailView;
import com.grandis.nova.catalog.edit.OptionValueAddRequest;
import com.grandis.nova.catalog.edit.OptionValueEditRequest;
import com.grandis.nova.catalog.edit.ProductEditRequest;
import com.grandis.nova.catalog.edit.SaleStatusChangeRequest;
import com.grandis.nova.catalog.edit.SaleStatusView;
import com.grandis.nova.catalog.edit.VariantAddRequest;
import com.grandis.nova.catalog.edit.VariantEditRequest;
import com.grandis.nova.catalog.edit.VisibilityChangeRequest;
import com.grandis.nova.catalog.edit.VisibilityView;
import com.grandis.nova.catalog.listing.AdminProductListItem;
import com.grandis.nova.catalog.listing.ProductListItem;
import com.grandis.nova.catalog.registration.ProductRegistrationRequest;
import com.grandis.nova.catalog.registration.RegistrationStatusView;
import com.grandis.nova.catalog.review.ReviewCreateRequest;
import com.grandis.nova.catalog.review.ReviewUpdateRequest;
import com.grandis.nova.catalog.review.ReviewView;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 문서를 켜면 서비스 이름 그룹(/v3/api-docs/catalog) 하나로 토큰 없이 열린다. 허브가 이 경로를 읽는다. */
@CatalogIntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true",
        "spring.application.name=catalog"
})
@DisplayName("catalog OpenAPI 문서 — 토큰 없이 열리고, 공개 표시가 실제 보안 규칙과 같다")
class OpenApiDocumentTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mockMvc;

    JsonNode doc;

    @BeforeEach
    void fetch() throws Exception {
        doc = JSON.readTree(mockMvc.perform(get("/v3/api-docs/catalog")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("그룹 문서와 Swagger UI 는 토큰 없이 열리고, 서비스 간 API 는 문서에 없다")
    void groupDocumentAndUiOpenWithoutToken() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        assertThat(doc.path("info").path("title").asString()).isEqualTo("catalog API");
        assertThat(doc.path("paths").propertyNames()).contains("/api/v1/products", "/api/v1/admin/products", "/api/v1/reviews")
                .noneMatch(path -> path.startsWith("/internal/"));
    }

    /**
     * 문서의 표시는 그 연산에 적용되는 security(연산 것, 없으면 문서 전체 것)로 읽는다 — 전체 요구가 빠지면 비공개 연산도 공개로 읽혀 여기서 걸린다.
     * 판정 축은 "토큰 없는 요청이 401 인가" 하나다. 경로 변수는 1, 본문은 {} 로 보낸다. 핸들러가 스스로 401 을 내는 연산(@CurrentCustomerId 를 받는
     * 회원 API)은 보안 규칙이 빠져도 여기서 같은 결과라 못 잡고, 역할(회원 토큰으로 관리자 API → 403)도 보지 않는다 — 그건 각 API 시험이 본다.
     */
    @Test
    @DisplayName("로그인 없이 표시된 연산은 정확히 토큰 없는 요청이 보안을 통과하는 연산이다 — 나머지는 토큰 없이 401")
    void publicMarksMatchTheSecurityChain() throws Exception {
        List<String> mismatched = new ArrayList<>();
        TreeSet<String> marked = new TreeSet<>();
        for (Map.Entry<String, JsonNode> entry : operations().entrySet()) {
            // 연산의 security 가 있으면 그것이, 없으면 문서 전체의 security 가 적용된다 — 빈 목록이면 인증 없이 부른다
            JsonNode effective = entry.getValue().has("security") ? entry.getValue().path("security") : doc.path("security");
            boolean markedPublic = effective.isEmpty();
            if (!markedPublic && effective.findValues("bearerAuth").isEmpty()) {
                mismatched.add(entry.getKey() + " → 인증 요구가 bearerAuth 가 아니다: " + effective);
            }
            if (markedPublic) {
                marked.add(entry.getKey());
            }
            String[] methodAndPath = entry.getKey().split(" ", 2);
            String path = methodAndPath[1].replaceAll("\\{[^}]+}", "1");
            int status = mockMvc.perform(request(HttpMethod.valueOf(methodAndPath[0]), path)
                    .contentType("application/json").content("{}")).andReturn().getResponse().getStatus();
            if (markedPublic == (status == 401)) {
                mismatched.add(entry.getKey() + " → " + status);
            }
        }
        assertThat(mismatched).as("공개 표시 ↔ 토큰 없는 요청의 401").isEmpty();
        assertThat(marked).containsExactlyInAnyOrder(
                "GET /api/v1/products", "GET /api/v1/products/{productId}", "GET /api/v1/products/{productId}/variants/{variantId}",
                "GET /api/v1/products/{productId}/reviews", "GET /api/v1/categories", "GET /api/v1/reviews");
    }

    @Test
    @DisplayName("본문을 문자열로 받는 연산도 실제로 읽는 요청 타입의 스키마를 싣는다")
    void stringBodiesDocumentTheirRequestType() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("POST /api/v1/admin/products", "ProductRegistrationRequest");
        expected.put("PATCH /api/v1/admin/products/{productId}", "ProductEditRequest");
        expected.put("POST /api/v1/admin/products/{productId}/option-values", "OptionValueAddRequest");
        expected.put("PATCH /api/v1/admin/products/{productId}/option-values/{valueId}", "OptionValueEditRequest");
        expected.put("POST /api/v1/admin/products/{productId}/variants", "VariantAddRequest");
        expected.put("PATCH /api/v1/admin/products/{productId}/variants/{variantId}", "VariantEditRequest");
        expected.put("PATCH /api/v1/admin/products/{productId}/sale-status", "SaleStatusChangeRequest");
        expected.put("PATCH /api/v1/admin/products/{productId}/visibility", "VisibilityChangeRequest");
        expected.put("POST /api/v1/reviews", "ReviewCreateRequest");
        expected.put("PATCH /api/v1/reviews/{reviewId}", "ReviewUpdateRequest");

        Map<String, String> documented = new LinkedHashMap<>();
        operations().forEach((name, operation) -> {
            JsonNode body = operation.path("requestBody");
            if (!body.isMissingNode()) {
                JsonNode schema = body.path("content").path("application/json").path("schema");
                documented.put(name, schema.path("$ref").asString("").replace("#/components/schemas/", ""));
            }
        });
        assertThat(documented).containsExactlyInAnyOrderEntriesOf(expected);
        expected.values().forEach(type -> assertThat(doc.path("components").path("schemas").has(type)).as(type).isTrue());
    }

    @Test
    @DisplayName("모든 연산에 이름(태그 · 요약)이 있고, 토큰에서 오는 회원 번호는 요청 파라미터가 아니다")
    void everyOperationIsNamedAndHidesTokenArguments() {
        List<String> unnamed = new ArrayList<>();
        List<String> leaked = new ArrayList<>();
        operations().forEach((name, operation) -> {
            String tag = operation.path("tags").path(0).asString("");
            if (tag.isBlank() || tag.endsWith("-controller") || operation.path("summary").asString("").isBlank()) {
                unnamed.add(name);
            }
            operation.path("parameters").forEach(parameter -> {
                if (parameter.path("name").asString("").equals("customerId")) {
                    leaked.add(name);
                }
            });
        });
        assertThat(unnamed).isEmpty();
        assertThat(leaked).isEmpty();
    }

    /**
     * 요청 · 응답 record 마다 문서 스키마의 칸 = record 의 칸. 이름(@Schema(name) 이 없으면 단순 이름)이 같은 두 타입은 컴포넌트 하나로 합쳐져
     * 한쪽 칸이 사라지고, isEmpty() 같은 getter 꼴 메서드는 swagger 가 칸으로 그려 Try it out 본문이 모르는 칸 400 이 된다 — 둘 다 여기서 걸린다.
     */
    @Test
    @DisplayName("요청 · 응답 스키마의 칸은 record 의 칸과 같고, 서로 다른 타입이 같은 스키마 이름을 쓰지 않는다")
    void schemasMatchTheRecords() {
        Map<String, Class<?>> byName = new LinkedHashMap<>();
        List<String> clashes = new ArrayList<>();
        List<String> mismatched = new ArrayList<>();
        java.util.ArrayDeque<Class<?>> queue = new java.util.ArrayDeque<>(List.of(
                ProductRegistrationRequest.class, ProductEditRequest.class, OptionValueAddRequest.class, OptionValueEditRequest.class,
                VariantAddRequest.class, VariantEditRequest.class, SaleStatusChangeRequest.class, VisibilityChangeRequest.class,
                ReviewCreateRequest.class, ReviewUpdateRequest.class,
                ProductDetailView.class, ProductListItem.class, AdminProductListItem.class, CategoryNode.class, ReviewView.class,
                RegistrationStatusView.class, SaleStatusView.class, VisibilityView.class));
        while (!queue.isEmpty()) {
            Class<?> type = queue.poll();
            Schema named = type.getAnnotation(Schema.class);
            String name = named != null && !named.name().isEmpty() ? named.name() : type.getSimpleName();
            Class<?> previous = byName.putIfAbsent(name, type);
            if (previous != null) {
                if (previous != type) {
                    clashes.add(name + ": " + previous.getName() + " ↔ " + type.getName());
                }
                continue;
            }
            TreeSet<String> components = new TreeSet<>();
            for (RecordComponent component : type.getRecordComponents()) {
                components.add(component.getName());
                recordsIn(component.getGenericType(), queue);
            }
            TreeSet<String> documented = new TreeSet<>(doc.path("components").path("schemas").path(name).path("properties").propertyNames());
            if (!documented.equals(components)) {
                mismatched.add(name + " 문서 " + documented + " ↔ record " + components);
            }
        }
        org.assertj.core.api.SoftAssertions.assertSoftly(soft -> {
            soft.assertThat(clashes).as("같은 스키마 이름").isEmpty();
            soft.assertThat(mismatched).as("스키마 칸 ↔ record 칸").isEmpty();
        });
    }

    private static void recordsIn(java.lang.reflect.Type type, java.util.Deque<Class<?>> queue) {
        if (type instanceof Class<?> c && c.isRecord()) {
            queue.add(c);
        } else if (type instanceof java.lang.reflect.ParameterizedType parameterized) {
            for (java.lang.reflect.Type argument : parameterized.getActualTypeArguments()) {
                recordsIn(argument, queue);
            }
        }
    }

    private Map<String, JsonNode> operations() {
        Map<String, JsonNode> operations = new LinkedHashMap<>();
        doc.path("paths").properties().forEach(path -> path.getValue().properties().forEach(
                method -> operations.put(method.getKey().toUpperCase() + " " + path.getKey(), method.getValue())));
        assertThat(operations).isNotEmpty();
        return operations;
    }
}
