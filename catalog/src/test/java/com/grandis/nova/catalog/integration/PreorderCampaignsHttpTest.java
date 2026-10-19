package com.grandis.nova.catalog.integration;

import com.grandis.nova.catalog.support.AccessTokens;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.web.ApiResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * preorder 회차 일정 API 를 진짜 HTTP 로 부른다 — preorder 주소를 이 시험이 띄운 HTTP 서버로 돌린다. 시험용 회차 대역은 끈다
 * (nova.test.fake-preorder-campaigns=false). 읽기 캐시가 꺼져 있어 /open 은 요청마다 새로 묻는다 — 답하지 않을 때 쓰는 마지막 값은 그대로 남는다.
 * 장애 때의 화면별 동작은 2026-10-19 결정이다: 목록은 마지막 값, 사전예약 상세 503, 홍보는 시각만 비움, 관리자 화면 · 수정 · 삭제는 503.
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "nova.test.fake-preorder-campaigns=false")
@DisplayName("preorder 회차 일정 API — 토큰 없이 부르고, 장애면 목록은 마지막 값 · 상세 503 · 홍보는 시각만 비움 · 관리자 503(실제 HTTP)")
class PreorderCampaignsHttpTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> SEEN_AUTHORIZATION = new CopyOnWriteArrayList<>();
    private static final List<String> SEEN_PATHS = new CopyOnWriteArrayList<>();
    /** 상품 id → [opensAt, closesAt]. 없으면 회차 없음. */
    private static final Map<String, Instant[]> CAMPAIGNS = new ConcurrentHashMap<>();
    private static volatile int failStatus;
    private static volatile String override;
    private static final HttpServer SERVER = start();

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PreorderCampaigns campaigns;
    /** 삭제가 먼저 묻는 order 의 드로우 증정품 확인 — 늘 "진행 중 드로우 없음" 으로 답해, 삭제의 503 이 preorder 에서 온 것만 남긴다. */
    @MockitoBean DrawGiftClient drawGifts;

    ShopFixtures fixtures;
    UUID category;

    @DynamicPropertySource
    static void preorder(DynamicPropertyRegistry registry) {
        registry.add("spring.http.serviceclient.preorder.base-url", () -> "http://localhost:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        category = fixtures.category();
        given(drawGifts.getGift(any())).willAnswer(call -> ApiResponse.ok(new DrawGiftClient.DrawGift(call.getArgument(0), false)));
        SEEN_AUTHORIZATION.clear();
        SEEN_PATHS.clear();
        failStatus = 0;
        override = null;
    }

    @Test
    @DisplayName("목록은 /open 의 회차를 catalog 시각으로 갈라 접수 중만 싣고 시각을 채운다 — 곧 열릴 회차는 빠지고, 회원 토큰이 있어도 preorder 에는 싣지 않는다")
    void listUsesOpenCampaignsWithoutToken() throws Exception {
        Instant now = Instant.now();
        UUID open = preorder("접수 중");
        UUID soon = preorder("곧 열림");
        campaign(open, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));
        campaign(soon, now.plus(Duration.ofMinutes(5)), now.plus(Duration.ofHours(1)));

        JsonNode items = list(true);

        assertThat(ids(items)).containsExactly(open.toString());
        assertThat(items.get(0).get("preorderStatus").asString()).isEqualTo("OPEN");
        assertThat(items.get(0).get("opensAt").isNull()).isFalse();
        assertThat(SEEN_PATHS).contains("/internal/preorders/campaigns/open");
        assertThat(SEEN_AUTHORIZATION).isNotEmpty().allMatch("null"::equals);
    }

    @Test
    @DisplayName("preorder 가 답하지 않으면 목록은 마지막으로 받은 회차를 쓴다 — 그 뒤에 열린 회차는 보이지 않는다(지금 값을 묻지 못했다)")
    void listKeepsLastOpenCampaignsWhenPreorderDown() throws Exception {
        Instant now = Instant.now();
        UUID before = preorder("장애 전");
        campaign(before, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));
        assertThat(ids(list(false))).containsExactly(before.toString());

        UUID after = preorder("장애 뒤");
        campaign(after, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));
        failStatus = 503;
        assertThat(ids(list(false))).as("마지막 값").containsExactly(before.toString());

        failStatus = 0;
        assertThat(ids(list(false))).as("대조군 — 다시 답하면 새 회차").containsExactlyInAnyOrder(before.toString(), after.toString());
    }

    @Test
    @DisplayName("사전예약 상세는 상품별 회차로 접수 단계를 채운다 — 회차가 없으면 404, preorder 가 답하지 않으면 503")
    void detailAsksCampaignOfThatProduct() throws Exception {
        Instant now = Instant.now();
        UUID product = preorder("상세");
        campaign(product, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));

        mockMvc.perform(get("/api/v1/products/{id}", product).with(AccessTokens.customer(UUID.randomUUID()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.campaign.status").value("OPEN"));
        assertThat(SEEN_PATHS).contains("/internal/preorders/campaigns?productIds=" + product);
        assertThat(SEEN_AUTHORIZATION).allMatch("null"::equals);

        failStatus = 503;
        mockMvc.perform(get("/api/v1/products/{id}", product)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("DEPENDENCY_UNAVAILABLE"));

        failStatus = 0;
        CAMPAIGNS.remove(product.toString());
        mockMvc.perform(get("/api/v1/products/{id}", product)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("홍보 상세는 preorder 가 답하지 않아도 200 — 사전예약 카드의 시각 · 접수 단계만 비운다. 답하면 회차가 없는 사전예약 카드는 뺀다")
    void promotionCardsLoseOnlyTimesWhenPreorderDown() throws Exception {
        Instant now = Instant.now();
        UUID withCampaign = preorder("홍보 회차 있음");
        UUID withoutCampaign = preorder("홍보 회차 없음");
        campaign(withCampaign, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)));
        UUID promotion = promotion(withCampaign, withoutCampaign);

        JsonNode cards = promotionCards(promotion);
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).get("productId").asString()).isEqualTo(withCampaign.toString());
        assertThat(cards.get(0).get("preorderStatus").asString()).isEqualTo("OPEN");

        failStatus = 503;
        JsonNode degraded = promotionCards(promotion);
        assertThat(degraded).as("준비를 가르지 못하니 빼지 않는다").hasSize(2);
        for (JsonNode card : degraded) {
            assertThat(card.get("opensAt").isNull()).isTrue();
            assertThat(card.get("preorderStatus").isNull()).isTrue();
        }
    }

    @Test
    @DisplayName("관리자 목록 · 상세는 preorder 가 답하지 않으면 503, 계약과 다른 응답이면 500 — 답하면 회차로 준비 · 시각을 채운다")
    void adminScreensFailClosed() throws Exception {
        Instant now = Instant.now();
        UUID product = preorder("관리자");
        campaign(product, now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(2)));

        mockMvc.perform(get("/api/v1/admin/products/{id}", product).with(AccessTokens.admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.registration.completed").value(true))
                .andExpect(jsonPath("$.data.product.campaign.status").value("BEFORE_OPEN"));
        mockMvc.perform(get("/api/v1/admin/products").param("q", "관리자").with(AccessTokens.admin())).andExpect(status().isOk());

        failStatus = 503;
        mockMvc.perform(get("/api/v1/admin/products/{id}", product).with(AccessTokens.admin())).andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/api/v1/admin/products").param("q", "관리자").with(AccessTokens.admin())).andExpect(status().isServiceUnavailable());

        failStatus = 0;
        override = "{\"success\":true,\"data\":{}}";
        mockMvc.perform(get("/api/v1/admin/products/{id}", product).with(AccessTokens.admin())).andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("오픈 판정이 필요한 수정 · 삭제는 preorder 가 답하지 않으면 503 으로 거절하고 아무것도 바꾸지 않는다 — 잘못된 본문은 그 전에 400")
    void editAndDeleteRejectWhenPreorderDown() throws Exception {
        Instant now = Instant.now();
        UUID product = preorder("수정");
        campaign(product, now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(2)));
        failStatus = 503;

        // 삭제한 상품은 회차를 묻지 않고 404 — preorder 장애와 상관없다
        UUID deleted = preorder("삭제함");
        jdbcTemplate.update("UPDATE products SET visible = 0, deleted_at = UTC_TIMESTAMP(6) WHERE id = UUID_TO_BIN(?)", deleted.toString());
        mockMvc.perform(patch("/api/v1/admin/products/{id}", deleted).with(AccessTokens.admin())
                .contentType(MediaType.APPLICATION_JSON).content("{ \"title\": \"고침\" }")).andExpect(status().isNotFound());
        // 본문 검증은 회차를 묻기 전 — 잘못된 요청은 preorder 장애와 상관없이 400 이고 preorder 를 부르지 않는다
        mockMvc.perform(patch("/api/v1/admin/products/{id}", product).with(AccessTokens.admin())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/v1/admin/products/{id}/sale-status", product).with(AccessTokens.admin())
                .contentType(MediaType.APPLICATION_JSON).content("{ \"status\": \"PAUSED\" }")).andExpect(status().isServiceUnavailable());
        mockMvc.perform(delete("/api/v1/admin/products/{id}", product).with(AccessTokens.admin())).andExpect(status().isServiceUnavailable());
        assertThat(SEEN_PATHS).as("두 요청 모두 preorder 에 회차를 한 번씩 물었다 — 503 을 다시 보내지 않는다(JDK HTTP 구현 고정, Apache 기본 재시도면 4번)").filteredOn(("/internal/preorders/campaigns?productIds=" + product)::equals).hasSize(2);

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM products WHERE id = UUID_TO_BIN(?)", String.class, product.toString())).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject("SELECT deleted_at IS NULL FROM products WHERE id = UUID_TO_BIN(?)", Boolean.class, product.toString())).isTrue();

        failStatus = 0;
        mockMvc.perform(patch("/api/v1/admin/products/{id}/sale-status", product).with(AccessTokens.admin())
                .contentType(MediaType.APPLICATION_JSON).content("{ \"status\": \"PAUSED\" }")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("오픈 판정이 없는 관리자 쓰기(사진 넣기)는 preorder 가 답하지 않아도 저장하고 201 — 응답의 회차는 null · 준비 false. 관리자 상세 조회는 503")
    void writeResponseLeavesCampaignEmptyWhenPreorderDown() throws Exception {
        Instant opensAt = Instant.now().plus(Duration.ofHours(2));
        String registered = mockMvc.perform(post("/api/v1/admin/products").with(AccessTokens.admin()).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "k-" + ShopFixtures.unique()).content("""
                        { "categoryId": "%s", "saleMode": "PREORDER", "title": "쓰기 응답", "brand": "APPLE", "modelName": "A0001", "visible": false, "basePrice": 1000000,
                          "optionAxes": [ { "key": "storage", "label": "용량", "values": [ { "value": "256GB" } ] } ],
                          "combinations": [ { "selections": { "storage": "256GB" } } ],
                          "campaign": { "opensAt": "%s", "closesAt": "%s" },
                          "shipmentBatches": [ { "batchNumber": 1, "positionFrom": 1, "positionTo": null, "estimatedShipStart": "2026-11-01", "estimatedShipEnd": "2026-11-07" } ] }
                        """.formatted(category, opensAt, opensAt.plus(Duration.ofDays(3))))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID product = UUID.fromString(JSON.readTree(registered).get("data").get("registration").get("productId").asString());
        campaign(product, opensAt, opensAt.plus(Duration.ofDays(3)));
        failStatus = 503;

        mockMvc.perform(post("/api/v1/admin/products/{id}/images", product).with(AccessTokens.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"kind\": \"DETAIL\", \"bundle\": \"유의사항\", \"url\": \"https://img/after-write.jpg\" }"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.product.campaign").isEmpty())
                .andExpect(jsonPath("$.data.registration.completed").value(false));
        assertThat(jdbcTemplate.queryForObject("SELECT JSON_SEARCH(options, 'one', 'https://img/after-write.jpg') IS NOT NULL FROM products WHERE id = UUID_TO_BIN(?)",
                Boolean.class, product.toString())).as("저장은 됐다").isTrue();
        mockMvc.perform(get("/api/v1/admin/products/{id}", product).with(AccessTokens.admin())).andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("상품별 회차는 한 번에 100개까지 실제 HTTP 로 묻는다 — productIds 를 반복해 실은 URL(약 4.8KB)도 받는다")
    void hundredProductIdsInOneRequest() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            ids.add(UUID.randomUUID());
        }

        assertThat(campaigns.find(ids)).isEmpty();

        assertThat(SEEN_PATHS).hasSize(1);
        assertThat(SEEN_PATHS.getFirst().split("productIds=", -1)).hasSize(101);
    }

    @Test
    @DisplayName("내부 상품 조회(1.3)는 preorder 를 부르지 않는다 — 사전예약 상품의 registrationCompleted 는 회차와 상관없이 true(순환 호출 금지)")
    void internalOptionsNeverCallPreorder() throws Exception {
        UUID product = preorder("내부 조회");
        failStatus = 503;

        mockMvc.perform(get("/internal/products/{id}/options", product).with(user("657").roles("USER"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.registrationCompleted").value(true));

        assertThat(SEEN_PATHS).isEmpty();
    }

    // ── 도우미 ──

    private UUID preorder(String title) {
        UUID product = fixtures.product(category, "PREORDER", "ACTIVE", title, "pc");
        fixtures.registration(product);
        fixtures.option(product, "ACTIVE", new BigDecimal("1000000"));
        return product;
    }

    private static void campaign(UUID product, Instant opensAt, Instant closesAt) {
        CAMPAIGNS.put(product.toString(), new Instant[]{opensAt, closesAt});
    }

    private JsonNode list(boolean withToken) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/products").param("categoryId", category.toString());
        if (withToken) {
            request = request.with(AccessTokens.customer(UUID.randomUUID()));
        }
        return JSON.readTree(mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data").get("items");
    }

    private UUID promotion(UUID... products) throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        String ids = String.join(",", Arrays.stream(products).map(id -> "\"" + id + "\"").toList());
        String body = """
                { "name": "회차 홍보", "startDate": "%s", "endDate": "%s", "thumbnailUrl": "https://img/t.jpg", "detailImageUrls": [], "productIds": [%s] }
                """.formatted(today, today, ids);
        String created = mockMvc.perform(post("/api/v1/admin/promotions").header("Idempotency-Key", "k-" + ShopFixtures.unique()).with(AccessTokens.admin())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JSON.readTree(created).get("data").get("promotionId").asString());
    }

    private JsonNode promotionCards(UUID promotion) throws Exception {
        return JSON.readTree(mockMvc.perform(get("/api/v1/promotions/{id}", promotion)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data").get("products");
    }

    private static List<String> ids(JsonNode items) {
        List<String> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.get("productId").asString()));
        return ids;
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/internal/preorders/campaigns", exchange -> {
                SEEN_AUTHORIZATION.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                String path = exchange.getRequestURI().getPath();
                String query = exchange.getRequestURI().getRawQuery();
                SEEN_PATHS.add(query == null ? path : path + "?" + URLDecoder.decode(query, StandardCharsets.UTF_8));
                if (failStatus != 0) {
                    respond(exchange, failStatus, "{\"success\":false,\"data\":null,\"error\":{\"code\":\"X\",\"message\":\"x\",\"details\":null}}");
                    return;
                }
                if (override != null) {
                    respond(exchange, 200, override);
                    return;
                }
                if (path.endsWith("/open")) {
                    Instant now = Instant.now();
                    StringBuilder items = new StringBuilder();
                    CAMPAIGNS.forEach((id, window) -> {
                        if (window[1].isAfter(now) && !window[0].isAfter(now.plus(Duration.ofMinutes(10)))) {
                            items.append(items.isEmpty() ? "" : ",").append(item(id, window));
                        }
                    });
                    respond(exchange, 200, "{\"success\":true,\"data\":{\"items\":[" + items + "],\"serverTime\":\"" + now + "\"}}");
                    return;
                }
                List<String> ids = new ArrayList<>();
                for (String pair : query == null ? new String[0] : query.split("&")) {
                    String[] kv = pair.split("=", 2);
                    if (kv[0].equals("productIds") && kv.length == 2) {
                        ids.addAll(Arrays.asList(URLDecoder.decode(kv[1], StandardCharsets.UTF_8).split(",")));
                    }
                }
                StringBuilder items = new StringBuilder();
                for (String id : ids) {
                    Instant[] window = CAMPAIGNS.get(id);
                    if (window != null) {
                        items.append(items.isEmpty() ? "" : ",").append(item(id, window));
                    }
                }
                respond(exchange, 200, "{\"success\":true,\"data\":{\"items\":[" + items + "]}}");
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String item(String id, Instant[] window) {
        return "{\"productId\":\"%s\",\"opensAt\":\"%s\",\"closesAt\":\"%s\"}".formatted(id, window[0], window[1]);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
