package com.grandis.nova.catalog.review;

import com.grandis.nova.catalog.support.AccessTokens;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 리뷰 작성의 내부 호출을 진짜 HTTP 로 본다 — order · member 주소를 이 시험이 띄운 HTTP 서버로 돌려,
 * 회원의 토큰이 그대로 실리는지(토큰 릴레이), 받는 쪽 응답(상태 · 오류 코드 · 본문 모양)이 회원에게 어떤 상태로 가는지 잰다.
 * 주문상품 id 마다 order · member 가 줄 답을 정해 둔다. member 호출은 같은 요청 안에서 order 바로 뒤에 오므로 마지막 주문상품으로 짝짓는다.
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
@DisplayName("리뷰 작성의 내부 호출 — 토큰 릴레이와 order · member 응답의 변환(실제 HTTP)")
class ReviewInternalCallsTest {

    private static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L + 2_000_000_000L);
    private static final Map<Long, Reply> ORDER = new ConcurrentHashMap<>();
    private static final Map<Long, Reply> MEMBER = new ConcurrentHashMap<>();
    private static final Map<String, String> SEEN_AUTHORIZATION = new ConcurrentHashMap<>();
    private static final HttpServer SERVER = start();

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    ShopFixtures fixtures;

    /** status · Content-Type · 본문. */
    record Reply(int status, String contentType, String body) {

        static Reply json(int status, String body) {
            return new Reply(status, "application/json", body);
        }

        static Reply error(int status, String code) {
            return json(status, "{\"success\":false,\"data\":null,\"error\":{\"code\":\"%s\",\"message\":\"x\",\"details\":null}}".formatted(code));
        }
    }

    @DynamicPropertySource
    static void internalApis(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + SERVER.getAddress().getPort();
        registry.add("spring.http.serviceclient.order.base-url", () -> base);
        registry.add("spring.http.serviceclient.member.base-url", () -> base);
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
    }

    @Test
    @DisplayName("order · member 에 회원이 보낸 토큰을 그대로 싣고, 둘 다 200 이면 201 로 저장한다")
    void relaysTheCallersTokenToBothServices() throws Exception {
        long customer = IDS.incrementAndGet();
        long orderItemId = deliveredFor(customer);
        String token = AccessTokens.customerToken(customer);

        write(token, orderItemId).andExpect(status().isCreated()).andExpect(jsonPath("$.data.authorName").value("김**"));

        assertThat(SEEN_AUTHORIZATION.get("/internal/order-items/" + orderItemId)).isEqualTo("Bearer " + token);
        assertThat(SEEN_AUTHORIZATION.get("/internal/customers/me#" + orderItemId)).isEqualTo("Bearer " + token);
    }

    @Test
    @DisplayName("order — ORDER_ITEM_NOT_FOUND 404 만 404, 공통 NOT_FOUND 404(경로 없음) · 403 · 읽을 수 없는 200 은 500, 401 은 401, 503 은 503")
    void orderRepliesMapToTheCallersStatus() throws Exception {
        long customer = IDS.incrementAndGet();
        String token = AccessTokens.customerToken(customer);
        expect(write(token, orderItem(customer, Reply.error(404, "ORDER_ITEM_NOT_FOUND"))), 404, "NOT_FOUND");
        expect(write(token, orderItem(customer, Reply.error(403, "FORBIDDEN"))), 500, "INTERNAL_ERROR");
        expect(write(token, orderItem(customer, new Reply(403, "text/html", "<html>blocked</html>"))), 500, "INTERNAL_ERROR");
        expect(write(token, orderItem(customer, Reply.error(404, "NOT_FOUND"))), 500, "INTERNAL_ERROR");
        expect(write(token, orderItem(customer, Reply.error(401, "UNAUTHENTICATED"))), 401, "UNAUTHENTICATED");
        expect(write(token, orderItem(customer, Reply.error(503, "DEPENDENCY_UNAVAILABLE"))), 503, "DEPENDENCY_UNAVAILABLE");
        expect(write(token, orderItem(customer, new Reply(200, "text/html", "<html>login</html>"))), 500, "INTERNAL_ERROR");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product_reviews WHERE customer_id = ?", Long.class, customer)).isZero();
    }

    @Test
    @DisplayName("member — 404(경로 없음) · 403 은 401 이 아니라 500, 401 은 401, 503 은 503")
    void memberRepliesMapToTheCallersStatus() throws Exception {
        long customer = IDS.incrementAndGet();
        String token = AccessTokens.customerToken(customer);
        expect(write(token, deliveredFor(customer, Reply.error(404, "NOT_FOUND"))), 500, "INTERNAL_ERROR");
        expect(write(token, deliveredFor(customer, Reply.error(403, "FORBIDDEN"))), 500, "INTERNAL_ERROR");
        expect(write(token, deliveredFor(customer, Reply.error(401, "UNAUTHENTICATED"))), 401, "UNAUTHENTICATED");
        expect(write(token, deliveredFor(customer, Reply.error(503, "DEPENDENCY_UNAVAILABLE"))), 503, "DEPENDENCY_UNAVAILABLE");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product_reviews WHERE customer_id = ?", Long.class, customer)).isZero();
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private long deliveredFor(long customer) {
        return deliveredFor(customer, Reply.json(200, "{\"success\":true,\"data\":{\"customerId\":%d,\"displayName\":\"김철수\"}}".formatted(customer)));
    }

    /** 배송 완료된 일반 주문상품 — member 는 reply 로 답한다. */
    private long deliveredFor(long customer, Reply member) {
        long id = IDS.incrementAndGet();
        Long product = fixtures.product("IN_STOCK", "ACTIVE");
        fixtures.inventory(fixtures.option(product, "ACTIVE", new BigDecimal("1000")), 1, 0, 0);
        ORDER.put(id, Reply.json(200, ("{\"success\":true,\"data\":{\"orderItemId\":%d,\"orderId\":1,\"productId\":%d,\"optionId\":1,"
                + "\"optionTitle\":\"블랙 / 256GB\",\"orderStatus\":\"DELIVERED\",\"orderSource\":\"BUY_NOW\"}}").formatted(id, product)));
        MEMBER.put(id, member);
        return id;
    }

    /** order 가 reply 로 답하는 주문상품. member 까지 가지 않는다. */
    private long orderItem(long customer, Reply order) {
        long id = deliveredFor(customer);
        ORDER.put(id, order);
        return id;
    }

    private ResultActions write(String token, long orderItemId) throws Exception {
        return mockMvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON)
                .content("{ \"orderItemId\": %d, \"rating\": 5, \"body\": \"좋아요\" }".formatted(orderItemId))
                .with(AccessTokens.withToken(token)));
    }

    private static void expect(ResultActions actions, int status, String code) throws Exception {
        actions.andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            long[] lastItem = {0};
            server.createContext("/internal/order-items/", exchange -> {
                long id = Long.parseLong(exchange.getRequestURI().getPath().substring("/internal/order-items/".length()));
                lastItem[0] = id;
                SEEN_AUTHORIZATION.put(exchange.getRequestURI().getPath(), String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                respond(exchange, ORDER.getOrDefault(id, Reply.error(404, "ORDER_ITEM_NOT_FOUND")));
            });
            server.createContext("/internal/customers/me", exchange -> {
                SEEN_AUTHORIZATION.put("/internal/customers/me#" + lastItem[0], String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                respond(exchange, MEMBER.getOrDefault(lastItem[0], Reply.error(404, "NOT_FOUND")));
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange exchange, Reply reply) throws IOException {
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", reply.contentType());
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
