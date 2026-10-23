package com.grandis.nova.catalog.review;

import com.grandis.nova.catalog.integration.MemberClient;
import com.grandis.nova.catalog.integration.OrderClient;
import com.grandis.nova.catalog.support.AccessTokens;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.MySqlContainerConfig;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.UuidBinary;
import com.grandis.nova.common.security.RevocationCheckProperties;
import com.grandis.nova.common.web.ApiResponse;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 상품 리뷰 API. order · member 내부 API 는 계약(contracts/order-internal.md · member-internal.md)대로 만든 대역이다 —
 * 실제 HTTP 와 토큰 릴레이 · 오류 변환은 {@link ReviewInternalCallsTest} 가 본다.
 */
@CatalogIntegrationTest
@AutoConfigureMockMvc
@DisplayName("상품 리뷰 — 일반 판매 상품의 배송 완료 주문상품 1건당 1개, 작성자만 수정 · 삭제, 조회는 공개")
class ReviewApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 형식만 맞으면 되는 자리(다른 칸의 400 을 보는 본문)의 주문상품 id. */
    private static final String ANY_ORDER_ITEM = "0199b2c4-0000-7000-8000-000000000001";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired RevocationCheckProperties revocation;
    @MockitoBean OrderClient orders;
    @MockitoBean MemberClient members;

    ShopFixtures fixtures;
    UUID customerId;
    UUID productId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        customerId = UUID.randomUUID();
        productId = readyInStock(fixtures.category());
        given(members.getMe()).willReturn(ApiResponse.ok(new MemberClient.Customer(customerId, "철수랑", "김철수")));
    }

    @Test
    @DisplayName("배송 완료된 내 주문상품에 쓴다 — 201, 가린 작성자명 · 주문 당시 옵션명 · 지금 상품명, 본문은 앞뒤 공백을 걷는다")
    void writesForDeliveredOrderItem() throws Exception {
        UUID orderItemId = deliveredItem(productId);

        JsonNode review = data(write(customerId, orderItemId, 5, "  배송이 빨라요  ").andExpect(status().isCreated()));

        assertThat(review.get("productId").asString()).isEqualTo(productId.toString());
        assertThat(review.get("productTitle").asString()).isEqualTo("Nova 1");
        assertThat(review.get("optionTitle").asString()).isEqualTo("블랙 / 256GB");
        assertThat(review.get("rating").asInt()).isEqualTo(5);
        assertThat(review.get("body").asString()).isEqualTo("배송이 빨라요");
        assertThat(review.get("authorName").asString()).isEqualTo("김**");
        assertThat(review.get("orderItemId").asString()).as("내 리뷰라 주문상품 id 를 싣는다").isEqualTo(orderItemId.toString());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product_reviews WHERE order_item_id = ?", Long.class, UuidBinary.toBytes(orderItemId)))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("작성자명은 실명을 가린 값이고, 실명을 안 채운 회원은 카카오 닉네임을 가린다")
    void authorNamePrefersRealName() throws Exception {
        given(members.getMe()).willReturn(ApiResponse.ok(new MemberClient.Customer(customerId, "철수랑", null)));
        JsonNode byNickname = data(write(customerId, deliveredItem(productId), 5, "닉네임으로").andExpect(status().isCreated()));
        assertThat(byNickname.get("authorName").asString()).isEqualTo("철**");

        given(members.getMe()).willReturn(ApiResponse.ok(new MemberClient.Customer(customerId, "철수랑", "  ")));
        JsonNode blankName = data(write(customerId, deliveredItem(productId), 5, "빈 실명").andExpect(status().isCreated()));
        assertThat(blankName.get("authorName").asString()).as("공백뿐인 실명은 없는 것으로 본다").isEqualTo("철**");

        given(members.getMe()).willReturn(ApiResponse.ok(new MemberClient.Customer(customerId, "철수랑", "김철수")));
        JsonNode byName = data(write(customerId, deliveredItem(productId), 5, "실명으로").andExpect(status().isCreated()));
        assertThat(byName.get("authorName").asString()).isEqualTo("김**");
    }

    @Test
    @DisplayName("배송 완료 전 · 사전예약 주문 · 사전예약 상품 · 드로우 당첨 주문이면 409 REVIEW_NOT_ALLOWED 이고 저장하지 않는다")
    void refusesWhenNotEligible() throws Exception {
        UUID shipped = nextId();
        stubItem(shipped, productId, "SHIPPED", "BUY_NOW");
        expectError(write(customerId, shipped, 5, "좋아요"), HttpStatus.CONFLICT, "REVIEW_NOT_ALLOWED");

        UUID preorderOrder = nextId();
        stubItem(preorderOrder, productId, "DELIVERED", "PREORDER");
        expectError(write(customerId, preorderOrder, 5, "좋아요"), HttpStatus.CONFLICT, "REVIEW_NOT_ALLOWED");

        UUID preorderProduct = fixtures.product("PREORDER", "ACTIVE");
        UUID onPreorderProduct = nextId();
        stubItem(onPreorderProduct, preorderProduct, "DELIVERED", "CART");
        expectError(write(customerId, onPreorderProduct, 5, "좋아요"), HttpStatus.CONFLICT, "REVIEW_NOT_ALLOWED");

        UUID drawOrder = nextId();
        stubItem(drawOrder, productId, "DELIVERED", "DRAW");
        expectError(write(customerId, drawOrder, 5, "좋아요"), HttpStatus.CONFLICT, "REVIEW_NOT_ALLOWED");

        UUID delivered = deliveredItem(productId);
        write(customerId, delivered, 5, "좋아요").andExpect(status().isCreated());   // 대조군 — 같은 상품 · 배송 완료 · 일반 주문
        UUID cartOrder = nextId();
        stubItem(cartOrder, productId, "DELIVERED", "CART");
        write(customerId, cartOrder, 4, "장바구니로 샀어요").andExpect(status().isCreated());   // 대조군 — 드로우 증정품과 같은 상품도 구매면 쓴다
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product_reviews WHERE customer_id = ?", Long.class, UuidBinary.toBytes(customerId)))
                .as("거절된 넷은 남지 않았다").isEqualTo(2L);
    }

    @Test
    @DisplayName("같은 주문상품에 두 번 쓰면 409 REVIEW_ALREADY_WRITTEN — DB UNIQUE 가 판정한다")
    void secondReviewForSameOrderItemIsConflict() throws Exception {
        UUID orderItemId = deliveredItem(productId);
        write(customerId, orderItemId, 5, "처음").andExpect(status().isCreated());
        expectError(write(customerId, orderItemId, 3, "두 번째"), HttpStatus.CONFLICT, "REVIEW_ALREADY_WRITTEN");
        assertThat(jdbcTemplate.queryForObject("SELECT body FROM product_reviews WHERE order_item_id = ?", String.class, UuidBinary.toBytes(orderItemId)))
                .isEqualTo("처음");
    }

    @Test
    @DisplayName("order 의 403 · 코드를 읽을 수 없는 404 는 500(없음과 구분), 401 은 401, 5xx · 연결 실패는 503 — 코드가 있는 404 는 ReviewInternalCallsTest")
    void orderFailuresMapToTheirStatus() throws Exception {
        UUID bareForbidden = nextId();
        given(orders.getOrderItem(bareForbidden)).willThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "f", null, null, null));
        expectError(write(customerId, bareForbidden, 5, "x"), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");

        UUID noRoute = nextId();
        given(orders.getOrderItem(noRoute)).willThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "nf", null, null, null));
        expectError(write(customerId, noRoute, 5, "x"), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");

        UUID unauthorized = nextId();
        given(orders.getOrderItem(unauthorized)).willThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "u", null, null, null));
        expectError(write(customerId, unauthorized, 5, "x"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        UUID conflict = nextId();
        given(orders.getOrderItem(conflict)).willThrow(HttpClientErrorException.create(HttpStatus.CONFLICT, "c", null, null, null));
        expectError(write(customerId, conflict, 5, "x"), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");

        UUID down = nextId();
        given(orders.getOrderItem(down)).willThrow(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "d", null, null, null));
        expectError(write(customerId, down, 5, "x"), HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");

        UUID unreachable = nextId();
        given(orders.getOrderItem(unreachable)).willThrow(new ResourceAccessException("connect timed out"));
        expectError(write(customerId, unreachable, 5, "x"), HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
    }

    @Test
    @DisplayName("응답이 계약과 다르면 500 이고 저장하지 않는다 — 물은 것과 다른 주문상품 · 필수 칸이 빈 응답 · catalog 에 없는 상품 · 토큰 주인이 아닌 회원")
    void contractViolationsAreIntegrationErrors() throws Exception {
        UUID other = nextId();
        given(orders.getOrderItem(eq(other))).willReturn(ApiResponse.ok(new OrderClient.OrderItem(UUID.randomUUID(), UUID.randomUUID(), productId, UUID.randomUUID(),
                "블랙 / 256GB", "DELIVERED", "BUY_NOW")));
        expectError(write(customerId, other, 5, "x"), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");

        // 빈 칸 — 검사가 없으면 결과가 달라지는 칸들이다: 경로가 비면 일반 주문으로 저장되고, 상태가 비면 409 로 잘못 답한다
        UUID noSource = nextId();
        given(orders.getOrderItem(eq(noSource))).willReturn(ApiResponse.ok(new OrderClient.OrderItem(noSource, UUID.randomUUID(), productId, UUID.randomUUID(),
                "블랙 / 256GB", "DELIVERED", null)));
        expectError(write(customerId, noSource, 5, "x"), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        UUID noStatus = nextId();
        given(orders.getOrderItem(eq(noStatus))).willReturn(ApiResponse.ok(new OrderClient.OrderItem(noStatus, UUID.randomUUID(), productId, UUID.randomUUID(),
                "블랙 / 256GB", null, "BUY_NOW")));
        expectError(write(customerId, noStatus, 5, "x"), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");

        UUID unknownProduct = nextId();
        given(orders.getOrderItem(eq(unknownProduct))).willReturn(ApiResponse.ok(new OrderClient.OrderItem(unknownProduct, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "블랙 / 256GB", "DELIVERED", "BUY_NOW")));
        expectError(write(customerId, unknownProduct, 5, "x"), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");

        UUID delivered = deliveredItem(productId);
        given(members.getMe()).willReturn(ApiResponse.ok(new MemberClient.Customer(UUID.randomUUID(), "철수랑", "김철수")));
        expectError(write(customerId, delivered, 5, "x"), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product_reviews WHERE customer_id = ?", Long.class, UuidBinary.toBytes(customerId)))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u00A0\u00A0", "\u200B", "\u2007", "\uFEFF", "\u3164\u3164", "\u3000 \t\n", "\u2800\u2800", "\uFE0F", "\u0001", "\u0301"})
    @DisplayName("보이는 글자가 없는 본문은 400 — strip() 이 못 걷는 공백 · 폭 없는 문자 · 한글 채움 문자 · 점자 빈칸 · 제어 문자 · 홀로 쓴 결합 문자만 있어도")
    void invisibleOnlyBodyIsBlank(String body) throws Exception {
        expectValidation(write(customerId, deliveredItem(productId), 5, body), "body");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{ \"orderItemId\": \"" + ANY_ORDER_ITEM + "\", \"rating\": 0, \"body\": \"x\" }|rating",
            "{ \"orderItemId\": \"" + ANY_ORDER_ITEM + "\", \"rating\": 6, \"body\": \"x\" }|rating",
            "{ \"orderItemId\": \"" + ANY_ORDER_ITEM + "\", \"rating\": 4.5, \"body\": \"x\" }|rating",
            "{ \"orderItemId\": \"" + ANY_ORDER_ITEM + "\", \"rating\": \"다섯\", \"body\": \"x\" }|rating",
            "{ \"orderItemId\": \"" + ANY_ORDER_ITEM + "\", \"rating\": 5, \"body\": \"   \" }|body",
            "{ \"rating\": 5, \"body\": \"x\" }|orderItemId",
            "{ \"orderItemId\": \"" + ANY_ORDER_ITEM + "\", \"rating\": 5, \"body\": \"x\", \"photo\": \"a\" }|photo"
    })
    @DisplayName("입력이 틀리면 그 칸의 400 — order 를 부르기 전에")
    void invalidInputIsFieldError(String testCase) throws Exception {
        String[] parts = testCase.split("\\|");
        expectValidation(mockMvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON).content(parts[0])
                .with(AccessTokens.customer(customerId))), parts[1]);
    }

    @Test
    @DisplayName("숫자 문자열 별점(\"5\")은 숫자로 받는다 — 관리자 API 와 같은 매퍼라 같은 동작이다(소수 · 숫자 아닌 문자열은 400)")
    void numericStringRatingIsAccepted() throws Exception {
        UUID orderItemId = deliveredItem(productId);
        mockMvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"orderItemId\": \"%s\", \"rating\": \"5\", \"body\": \"x\" }".formatted(orderItemId))
                        .with(AccessTokens.customer(customerId)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.rating").value(5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ㅋㅋ", "e\u0301", "\uD83D\uDC4D\u2764\uFE0F", "\u1100\u1161", "1\uFE0F\u20E3"})
    @DisplayName("대조 — 짧은 글 · 결합 문자가 붙은 글자 · 이모지 · 풀어 쓴 한글 · 키캡은 보이는 글이라 들어간다")
    void visibleShortBodiesPass(String body) throws Exception {
        write(customerId, deliveredItem(productId), 5, body).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("본문은 앞뒤 공백을 뺀 2,000자까지 — 2,001자는 400, 공백을 붙여 2,000자를 넘겨도 걷으면 들어간다")
    void bodyLengthCountsAfterStripping() throws Exception {
        expectValidation(write(customerId, deliveredItem(productId), 5, "가".repeat(2001)), "body");
        write(customerId, deliveredItem(productId), 5, "  " + "가".repeat(2000) + "  ").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("작성자는 별점 · 본문을 따로 고치고 지울 수 있다. 남의 리뷰는 404, 빈 수정은 400")
    void authorRevisesAndDeletes() throws Exception {
        UUID orderItemId = deliveredItem(productId);
        UUID reviewId = UUID.fromString(data(write(customerId, orderItemId, 5, "처음").andExpect(status().isCreated())).get("reviewId").asString());

        JsonNode rated = data(revise(customerId, reviewId, "{ \"rating\": 3 }").andExpect(status().isOk()));
        assertThat(rated.get("rating").asInt()).isEqualTo(3);
        assertThat(rated.get("body").asString()).as("보내지 않은 칸은 그대로").isEqualTo("처음");
        JsonNode rewritten = data(revise(customerId, reviewId, "{ \"body\": \" 고쳤어요 \" }").andExpect(status().isOk()));
        assertThat(rewritten.get("body").asString()).isEqualTo("고쳤어요");
        assertThat(rewritten.get("rating").asInt()).isEqualTo(3);

        UUID stranger = nextId();
        expectError(revise(stranger, reviewId, "{ \"rating\": 1 }"), HttpStatus.NOT_FOUND, "NOT_FOUND");
        expectError(mockMvc.perform(delete("/api/v1/reviews/{id}", reviewId).with(AccessTokens.customer(stranger))),
                HttpStatus.NOT_FOUND, "NOT_FOUND");
        expectValidation(revise(customerId, reviewId, "{}"), "body");

        mockMvc.perform(delete("/api/v1/reviews/{id}", reviewId).with(AccessTokens.customer(customerId)))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product_reviews WHERE id = ?", Long.class, UuidBinary.toBytes(reviewId))).isZero();
        write(customerId, orderItemId, 4, "다시").andExpect(status().isCreated());   // 지운 뒤에는 같은 주문상품에 다시 쓸 수 있다
    }

    @Test
    @DisplayName("상품 리뷰는 비로그인도 본다 — 최신순 · 페이징, 공개 목록에는 주문상품 id 가 없다. 보이지 않는 상품(준비 전 사전예약 포함)은 상세처럼 404")
    void productReviewsArePublicNewestFirst() throws Exception {
        write(customerId, deliveredItem(productId), 4, "첫째").andExpect(status().isCreated());
        write(customerId, deliveredItem(productId), 5, "둘째").andExpect(status().isCreated());
        write(customerId, deliveredItem(productId), 3, "셋째").andExpect(status().isCreated());

        JsonNode page = data(mockMvc.perform(get("/api/v1/products/{id}/reviews", productId).param("size", "2"))
                .andExpect(status().isOk()));
        assertThat(page.get("total").asLong()).isEqualTo(3);
        assertThat(page.get("hasNext").asBoolean()).isTrue();
        assertThat(page.get("items").get(0).get("body").asString()).isEqualTo("셋째");
        assertThat(page.get("items").get(1).get("body").asString()).isEqualTo("둘째");
        assertThat(page.get("items").get(0).get("orderItemId").isNull()).isTrue();
        JsonNode second = data(mockMvc.perform(get("/api/v1/products/{id}/reviews", productId).param("size", "2").param("page", "1"))
                .andExpect(status().isOk()));
        assertThat(second.get("items")).extracting(item -> item.get("body").asString()).containsExactly("첫째");
        assertThat(second.get("total").asLong()).isEqualTo(3);
        // 범위 밖 쪽 — 빈 목록이어도 total 은 그대로다(건수와 쪽을 한 문장으로 읽는다)
        JsonNode beyond = data(mockMvc.perform(get("/api/v1/products/{id}/reviews", productId).param("size", "2").param("page", "5"))
                .andExpect(status().isOk()));
        assertThat(beyond.get("items")).isEmpty();
        assertThat(beyond.get("total").asLong()).isEqualTo(3);
        assertThat(beyond.get("hasNext").asBoolean()).isFalse();

        jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = ?", UuidBinary.toBytes(productId));
        mockMvc.perform(get("/api/v1/products/{id}/reviews", productId)).andExpect(status().isNotFound());
        // 상품 상세와 같은 규칙 — 재고 행이 없는(준비 전) 일반 상품 · 없는 상품도 404
        UUID notReady = fixtures.product("IN_STOCK", "ACTIVE");
        fixtures.option(notReady, "ACTIVE", new BigDecimal("1000"));
        mockMvc.perform(get("/api/v1/products/{id}/reviews", notReady)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/products/{id}/reviews", UUID.randomUUID())).andExpect(status().isNotFound());
        // 사전예약은 preorder 회차가 있어야 준비다 — 회차 전 404, 회차가 생기면 200(준비는 트랜잭션 밖에서 preorder 에 묻는다)
        UUID preorder = fixtures.product("PREORDER", "ACTIVE");
        mockMvc.perform(get("/api/v1/products/{id}/reviews", preorder)).andExpect(status().isNotFound());
        fixtures.campaign(preorder, java.time.Instant.now().plus(Duration.ofDays(1)), java.time.Instant.now().plus(Duration.ofDays(2)));
        mockMvc.perform(get("/api/v1/products/{id}/reviews", preorder)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("상품 리뷰 — 상품 확인 · 준비 한 문장, 후기 목록 · 건수 한 문장. 트랜잭션을 열지 않는다(격리 · 읽기 전용 전환 문장이 없다)")
    void productReviewsReadWithoutTransaction() throws Exception {
        write(customerId, deliveredItem(productId), 5, "한 문장").andExpect(status().isCreated());
        List<String> statements = statementsOf("argument LIKE 'SELECT p.sale_mode, p.visible%' OR argument LIKE '%LEFT JOIN LATERAL%'",
                () -> mockMvc.perform(get("/api/v1/products/{id}/reviews", productId)).andExpect(status().isOk()));

        // 양성 대조군 — 로그가 이 조회를 잡았다
        assertThat(statements).as("상품 확인 · 준비").filteredOn(sql -> sql.contains("FROM products p WHERE p.id")).hasSize(1);
        assertThat(statements).as("후기 목록 · 건수").filteredOn(sql -> sql.contains("product_reviews")).hasSize(1);
        assertThat(statements).as("트랜잭션 준비 · 정리 문장")
                .noneMatch(sql -> sql.contains("TRANSACTION ISOLATION") || sql.contains("READ ONLY") || sql.startsWith("SET autocommit"));
    }

    @Test
    @DisplayName("모아보기 · 내 리뷰 — 목록 · 건수 한 문장. 트랜잭션을 열지 않는다(격리 · 읽기 전용 전환 문장이 없다)")
    void visibleAndMyReviewsReadWithoutTransaction() throws Exception {
        write(customerId, deliveredItem(productId), 5, "한 문장").andExpect(status().isCreated());
        for (MockHttpServletRequestBuilder request : List.of(get("/api/v1/reviews"), get("/api/v1/reviews/mine").with(AccessTokens.customer(customerId)))) {
            List<String> statements = statementsOf("argument LIKE '%LEFT JOIN LATERAL%'", () -> mockMvc.perform(request).andExpect(status().isOk()));

            assertThat(statements).as("양성 대조군 — 목록 · 건수").filteredOn(sql -> sql.contains("product_reviews")).hasSize(1);
            assertThat(statements).as("트랜잭션 준비 · 정리 문장")
                    .noneMatch(sql -> sql.contains("TRANSACTION ISOLATION") || sql.contains("READ ONLY") || sql.startsWith("SET autocommit"));
        }
    }

    /** MySQL 일반 로그로 한 호출이 보낸 문장 — whereMarker 에 걸린 문장을 보낸 연결의 문장만(같은 서버를 쓰는 다른 작업의 문장을 섞지 않는다). */
    private List<String> statementsOf(String whereMarker, ThrowingCall call) throws Exception {
        var mysql = MySqlContainerConfig.get();
        List<String> statements = new ArrayList<>();
        try (Connection root = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             Statement statement = root.createStatement()) {
            statement.execute("SET GLOBAL log_output = 'TABLE'");
            statement.execute("SET GLOBAL general_log = 'ON'");
            statement.execute("TRUNCATE TABLE mysql.general_log");
            try {
                call.run();
            } finally {
                statement.execute("SET GLOBAL general_log = 'OFF'");
            }
            // 트랜잭션을 열었다면 준비 문장도 이 연결로 간다
            try (ResultSet rs = statement.executeQuery("SELECT argument FROM mysql.general_log WHERE command_type = 'Query' AND thread_id IN"
                    + " (SELECT thread_id FROM mysql.general_log WHERE " + whereMarker + ") ORDER BY event_time")) {
                while (rs.next()) {
                    statements.add(new String(rs.getBytes(1), StandardCharsets.UTF_8));
                }
            }
        }
        return statements;
    }

    private interface ThrowingCall {
        void run() throws Exception;
    }

    @Test
    @DisplayName("모아보기는 비로그인도 — 상위 카테고리는 하위 포함, 다른 카테고리 · 비공개 상품의 리뷰는 빠진다. 주문상품 id 는 싣지 않고, 범위 밖 쪽도 건수는 그대로")
    void visibleReviewsFilterByCategory() throws Exception {
        UUID parent = fixtures.category();
        UUID child = fixtures.childCategory(parent, "삼성");
        UUID inParent = readyInStock(parent);
        UUID inChild = readyInStock(child);
        UUID elsewhere = readyInStock(fixtures.category());
        UUID hidden = readyInStock(child);
        write(customerId, deliveredItem(inParent), 5, "상위").andExpect(status().isCreated());
        write(customerId, deliveredItem(inChild), 5, "하위").andExpect(status().isCreated());
        write(customerId, deliveredItem(elsewhere), 5, "다른 곳").andExpect(status().isCreated());
        write(customerId, deliveredItem(hidden), 5, "숨김").andExpect(status().isCreated());
        jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = ?", UuidBinary.toBytes(hidden));

        JsonNode byParent = data(mockMvc.perform(get("/api/v1/reviews").param("categoryId", parent.toString())).andExpect(status().isOk()));
        assertThat(bodies(byParent)).containsExactly("하위", "상위");
        JsonNode byChild = data(mockMvc.perform(get("/api/v1/reviews").param("categoryId", child.toString())).andExpect(status().isOk()));
        assertThat(bodies(byChild)).containsExactly("하위");
        JsonNode all = data(mockMvc.perform(get("/api/v1/reviews").param("size", "100")).andExpect(status().isOk()));
        assertThat(bodies(all)).contains("상위", "하위", "다른 곳").doesNotContain("숨김");
        byParent.get("items").forEach(item -> assertThat(item.path("orderItemId").isMissingNode() || item.path("orderItemId").isNull())
                .as("모아보기는 공개 응답이라 주문상품 id 를 싣지 않는다").isTrue());

        // 범위 밖 쪽도 건수는 그대로 — 모아보기 · 내 리뷰 모두
        JsonNode beyond = data(mockMvc.perform(get("/api/v1/reviews").param("categoryId", parent.toString()).param("size", "1").param("page", "5"))
                .andExpect(status().isOk()));
        assertThat(beyond.get("total").asLong()).isEqualTo(2);
        assertThat(beyond.get("items")).isEmpty();
        JsonNode mineBeyond = data(mockMvc.perform(get("/api/v1/reviews/mine").param("size", "1").param("page", "9")
                .with(AccessTokens.customer(customerId))).andExpect(status().isOk()));
        assertThat(mineBeyond.get("total").asLong()).isEqualTo(4);
        assertThat(mineBeyond.get("items")).isEmpty();
    }

    @Test
    @DisplayName("내 리뷰는 로그인한 회원만 — orderItemId 로 그 주문상품(여러 개 가능)의 리뷰를 고른다(주문 내역의 리뷰 쓰기 / 수정). 빈 원소 · 100개 초과는 400")
    void myReviewsByOrderItem() throws Exception {
        UUID written = deliveredItem(productId);
        UUID notWritten = deliveredItem(productId);
        write(customerId, written, 5, "내 리뷰").andExpect(status().isCreated());

        JsonNode mine = data(mockMvc.perform(get("/api/v1/reviews/mine").param("orderItemId", written.toString())
                .with(AccessTokens.customer(customerId))).andExpect(status().isOk()));
        assertThat(mine.get("total").asLong()).isEqualTo(1);
        assertThat(mine.get("items").get(0).get("orderItemId").asString()).isEqualTo(written.toString());
        JsonNode none = data(mockMvc.perform(get("/api/v1/reviews/mine").param("orderItemId", notWritten.toString())
                .with(AccessTokens.customer(customerId))).andExpect(status().isOk()));
        assertThat(none.get("total").asLong()).isZero();
        JsonNode others = data(mockMvc.perform(get("/api/v1/reviews/mine").with(AccessTokens.customer(nextId()))).andExpect(status().isOk()));
        assertThat(others.get("total").asLong()).as("남의 리뷰는 내 리뷰가 아니다").isZero();

        // 여러 주문상품을 한 번에 — 여러 번 · 쉼표 모두, 쓴 것만 온다
        UUID alsoWritten = deliveredItem(productId);
        write(customerId, alsoWritten, 4, "또 쓴 리뷰").andExpect(status().isCreated());
        for (var request : List.of(
                get("/api/v1/reviews/mine").param("orderItemId", written.toString(), notWritten.toString(), alsoWritten.toString()),
                get("/api/v1/reviews/mine").param("orderItemId", written + "," + notWritten + "," + alsoWritten))) {
            JsonNode several = data(mockMvc.perform(request.with(AccessTokens.customer(customerId))).andExpect(status().isOk()));
            List<String> orderItems = new java.util.ArrayList<>();
            several.get("items").forEach(item -> orderItems.add(item.get("orderItemId").asString()));
            assertThat(orderItems).containsExactlyInAnyOrder(written.toString(), alsoWritten.toString());
            assertThat(several.get("total").asLong()).isEqualTo(2);
        }
        JsonNode mixed = data(mockMvc.perform(get("/api/v1/reviews/mine").param("orderItemId", written + "," + notWritten, alsoWritten.toString())
                .with(AccessTokens.customer(customerId))).andExpect(status().isOk()));
        assertThat(mixed.get("total").asLong()).as("여러 번 · 쉼표를 섞어도").isEqualTo(2);
        mockMvc.perform(get("/api/v1/reviews/mine").param("orderItemId", "not-a-uuid").with(AccessTokens.customer(customerId)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/reviews/mine").param("orderItemId", written + ",").with(AccessTokens.customer(customerId)))
                .andExpect(status().isBadRequest());
        String tooMany = java.util.stream.IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID().toString()).collect(java.util.stream.Collectors.joining(","));
        mockMvc.perform(get("/api/v1/reviews/mine").param("orderItemId", tooMany).with(AccessTokens.customer(customerId)))
                .andExpect(status().isBadRequest());
        // 같은 id 는 한 번 — 상한은 거른 뒤 센다
        String sameTimes = java.util.stream.IntStream.range(0, 101).mapToObj(i -> written.toString()).collect(java.util.stream.Collectors.joining(","));
        JsonNode same = data(mockMvc.perform(get("/api/v1/reviews/mine").param("orderItemId", sameTimes).with(AccessTokens.customer(customerId)))
                .andExpect(status().isOk()));
        assertThat(same.get("total").asLong()).isEqualTo(1);
        String hundredAndDuplicate = java.util.stream.Stream.concat(java.util.stream.Stream.of(written.toString()),
                java.util.stream.IntStream.range(0, 99).mapToObj(i -> UUID.randomUUID().toString())).collect(java.util.stream.Collectors.joining(",")) + "," + written;
        mockMvc.perform(get("/api/v1/reviews/mine").param("orderItemId", hundredAndDuplicate).with(AccessTokens.customer(customerId)))
                .andExpect(status().isOk());
        // size 를 주지 않으면 보낸 id 수 — 기본 20 을 넘는 id 를 보내도 한 쪽에 다 온다
        List<UUID> many = new java.util.ArrayList<>(List.of(written, alsoWritten));
        java.util.stream.IntStream.range(0, 30).forEach(i -> many.add(UUID.randomUUID()));
        JsonNode sized = data(mockMvc.perform(get("/api/v1/reviews/mine")
                .param("orderItemId", many.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",")))
                .with(AccessTokens.customer(customerId))).andExpect(status().isOk()));
        assertThat(sized.get("size").asInt()).isEqualTo(32);
        JsonNode blank = data(mockMvc.perform(get("/api/v1/reviews/mine").param("orderItemId", "").with(AccessTokens.customer(customerId)))
                .andExpect(status().isOk()));
        assertThat(blank.get("total").asLong()).as("값이 통째로 비면 거르지 않는다").isEqualTo(2);

        jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = ?", UuidBinary.toBytes(productId));
        JsonNode afterHidden = data(mockMvc.perform(get("/api/v1/reviews/mine").with(AccessTokens.customer(customerId))).andExpect(status().isOk()));
        assertThat(afterHidden.get("total").asLong()).as("내 리뷰는 상품이 비공개가 돼도 보인다").isEqualTo(2);
    }

    @Test
    @DisplayName("쓰기 · 내 리뷰는 회원만 — 익명 401, 관리자 403. 상품 리뷰 · 모아보기는 익명도 200")
    void accessRules() throws Exception {
        String body = "{ \"orderItemId\": \"" + ANY_ORDER_ITEM + "\", \"rating\": 5, \"body\": \"x\" }";
        mockMvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON).content(body).with(AccessTokens.admin()))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/reviews/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{ \"rating\": 1 }"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/reviews/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/reviews/mine")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/products/{id}/reviews", productId)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/reviews")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("폐기 조회가 실패하면 리뷰 쓰기(작성 · 수정 · 삭제)는 닫힌다 — 공통 기본 셋(관리자 · 재발급 · 내 정보)도 그대로 남는다. 조회는 열린다")
    void reviewWritesFailClosed() {
        assertThat(revocation.failClosedPaths()).as("공통 기본 목록을 하나도 잃지 않는다")
                .containsAll(RevocationCheckProperties.DEFAULT_FAIL_CLOSED_PATHS)
                .contains("POST /api/v1/reviews", "PATCH /api/v1/reviews/*", "DELETE /api/v1/reviews/*");
        assertThat(revocation.failClosedPaths()).noneMatch(path -> path.startsWith("GET"));
    }

    @Test
    @DisplayName("작성자명은 첫 글자 + ** — 이모지 같은 보충 문자도 한 글자로, 비었으면 ***")
    void maskKeepsOnlyTheFirstCodePoint() {
        assertThat(ReviewService.mask("김철수")).isEqualTo("김**");
        assertThat(ReviewService.mask("A")).isEqualTo("A**");
        assertThat(ReviewService.mask("😀abc")).isEqualTo("😀**");
        assertThat(ReviewService.mask("  ")).isEqualTo("***");
        assertThat(ReviewService.mask(null)).isEqualTo("***");
    }

    /**
     * 별점만 고치는 요청이 그사이 다른 요청이 고친 본문을 되돌리지 않는다. 결정적으로 본다: 다른 트랜잭션이 본문을 고친 채 커밋하지 않고,
     * 별점 고치기가 그 리뷰 행에서 기다리는 것을 확인한 뒤 커밋한다. 잠그고 읽어야 커밋된 본문을 보고 별점만 바꾼다.
     */
    @Test
    @DisplayName("별점만 고쳐도 그사이 다른 요청이 고친 본문을 되돌리지 않는다")
    void ratingRevisionKeepsConcurrentBodyRevision() throws Exception {
        UUID orderItemId = deliveredItem(productId);
        UUID reviewId = UUID.fromString(data(write(customerId, orderItemId, 3, "original").andExpect(status().isCreated()))
                .get("reviewId").asString());
        CountDownLatch rewritten = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CompletableFuture<Void> other = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            jdbcTemplate.update("UPDATE product_reviews SET body = 'new body' WHERE id = ?", (Object) UuidBinary.toBytes(reviewId));
            rewritten.countDown();
            awaitLatch(commit);
        }));
        awaitLatch(rewritten);
        CompletableFuture<Integer> rating = CompletableFuture.supplyAsync(() -> {
            try {
                return revise(customerId, reviewId, "{ \"rating\": 5 }").andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Awaitility.await("별점 고치기가 리뷰 행에서 기다리지 않았다").atMost(Duration.ofSeconds(10)).until(() -> jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.PROCESSLIST
                 WHERE COMMAND = 'Query' AND ID <> CONNECTION_ID()
                   AND LOWER(INFO) LIKE '%from product_reviews %for update%'""", Long.class) > 0);
        commit.countDown();
        other.get(20, TimeUnit.SECONDS);

        assertThat(rating.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT rating, body FROM product_reviews WHERE id = ?", (Object) UuidBinary.toBytes(reviewId));
        assertThat(((Number) row.get("rating")).intValue()).isEqualTo(5);
        assertThat(row.get("body")).isEqualTo("new body");
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("기다리다 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 공개 · 판매 중 · 재고 행이 있는(준비된) 일반 상품 — 상품 상세가 보여 주는 상품. */
    private UUID readyInStock(UUID categoryId) {
        UUID product = fixtures.product(categoryId, "IN_STOCK", "ACTIVE");
        fixtures.inventory(fixtures.option(product, "ACTIVE", new BigDecimal("1000")), 5, 0, 0);
        return product;
    }

    private UUID deliveredItem(UUID product) {
        UUID orderItemId = nextId();
        stubItem(orderItemId, product, "DELIVERED", "BUY_NOW");
        return orderItemId;
    }

    private void stubItem(UUID orderItemId, UUID product, String orderStatus, String source) {
        given(orders.getOrderItem(eq(orderItemId))).willReturn(ApiResponse.ok(new OrderClient.OrderItem(orderItemId, UUID.randomUUID(),
                product, UUID.randomUUID(), "블랙 / 256GB", orderStatus, source)));
    }

    private static UUID nextId() {
        return UUID.randomUUID();
    }

    private ResultActions write(UUID customer, UUID orderItemId, int rating, String body) throws Exception {
        String json = JSON.writeValueAsString(java.util.Map.of("orderItemId", orderItemId, "rating", rating, "body", body));
        return mockMvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON).content(json)
                .with(AccessTokens.customer(customer)));
    }

    private ResultActions revise(UUID customer, UUID reviewId, String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/reviews/{id}", reviewId).contentType(MediaType.APPLICATION_JSON).content(body)
                .with(AccessTokens.customer(customer)));
    }

    @Test
    @DisplayName("리뷰 목록 셋 다 쪽은 앞 10,000개 안에서 시작 — page × size 가 10,000 이상이면 400 VALIDATION_FAILED(page)")
    void capsListDepth() throws Exception {
        mockMvc.perform(get("/api/v1/reviews").param("size", "100").param("page", "99")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/reviews/mine").param("size", "100").param("page", "99").with(AccessTokens.customer(nextId())))
                .andExpect(status().isOk());

        expectValidation(mockMvc.perform(get("/api/v1/reviews").param("size", "100").param("page", "100")), "page");
        expectValidation(mockMvc.perform(get("/api/v1/reviews/mine").param("size", "100").param("page", "100")
                .with(AccessTokens.customer(nextId()))), "page");
        expectValidation(mockMvc.perform(get("/api/v1/products/{id}/reviews", UUID.randomUUID()).param("size", "100").param("page", "100")),
                "page");
    }

    private static java.util.List<String> bodies(JsonNode page) {
        java.util.List<String> out = new java.util.ArrayList<>();
        page.get("items").forEach(item -> out.add(item.get("body").asString()));
        return out;
    }

    private static void expectError(ResultActions actions, HttpStatus status, String code) throws Exception {
        actions.andExpect(status().is(status.value())).andExpect(jsonPath("$.error.code").value(code));
    }

    private static void expectValidation(ResultActions actions, String field) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.violations[0].field").value(field));
    }

    private static JsonNode data(ResultActions actions) throws Exception {
        return JSON.readTree(actions.andReturn().getResponse().getContentAsString()).get("data");
    }
}
