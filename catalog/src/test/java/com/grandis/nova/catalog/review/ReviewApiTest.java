package com.grandis.nova.catalog.review;

import com.grandis.nova.catalog.integration.MemberClient;
import com.grandis.nova.catalog.integration.OrderClient;
import com.grandis.nova.catalog.support.AccessTokens;
import com.grandis.nova.catalog.support.CatalogIntegrationTest;
import com.grandis.nova.catalog.support.ShopFixtures;
import com.grandis.nova.common.security.RevocationCheckProperties;
import com.grandis.nova.common.web.ApiResponse;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

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
    private static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RevocationCheckProperties revocation;
    @MockitoBean OrderClient orders;
    @MockitoBean MemberClient members;

    ShopFixtures fixtures;
    long customerId;
    Long productId;

    @BeforeEach
    void setUp() {
        fixtures = new ShopFixtures(jdbcTemplate);
        customerId = IDS.incrementAndGet();
        productId = readyInStock(fixtures.category());
        given(members.getMe()).willReturn(ApiResponse.ok(new MemberClient.Customer(customerId, "철수랑", "김철수")));
    }

    @Test
    @DisplayName("배송 완료된 내 주문상품에 쓴다 — 201, 가린 작성자명 · 주문 당시 옵션명 · 지금 상품명, 본문은 앞뒤 공백을 걷는다")
    void writesForDeliveredOrderItem() throws Exception {
        long orderItemId = deliveredItem(productId);

        JsonNode review = data(write(customerId, orderItemId, 5, "  배송이 빨라요  ").andExpect(status().isCreated()));

        assertThat(review.get("productId").asLong()).isEqualTo(productId);
        assertThat(review.get("productTitle").asString()).isEqualTo("Nova 1");
        assertThat(review.get("optionTitle").asString()).isEqualTo("블랙 / 256GB");
        assertThat(review.get("rating").asInt()).isEqualTo(5);
        assertThat(review.get("body").asString()).isEqualTo("배송이 빨라요");
        assertThat(review.get("authorName").asString()).isEqualTo("김**");
        assertThat(review.get("orderItemId").asLong()).as("내 리뷰라 주문상품 id 를 싣는다").isEqualTo(orderItemId);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product_reviews WHERE order_item_id = ?", Long.class, orderItemId))
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
    @DisplayName("배송 완료 전 · 사전예약 주문 · 사전예약 상품이면 409 REVIEW_NOT_ALLOWED 이고 저장하지 않는다")
    void refusesWhenNotEligible() throws Exception {
        long shipped = nextId();
        stubItem(shipped, productId, "SHIPPED", "BUY_NOW");
        expectError(write(customerId, shipped, 5, "좋아요"), HttpStatus.CONFLICT, "REVIEW_NOT_ALLOWED");

        long preorderOrder = nextId();
        stubItem(preorderOrder, productId, "DELIVERED", "PREORDER");
        expectError(write(customerId, preorderOrder, 5, "좋아요"), HttpStatus.CONFLICT, "REVIEW_NOT_ALLOWED");

        Long preorderProduct = fixtures.product("PREORDER", "ACTIVE");
        long onPreorderProduct = nextId();
        stubItem(onPreorderProduct, preorderProduct, "DELIVERED", "CART");
        expectError(write(customerId, onPreorderProduct, 5, "좋아요"), HttpStatus.CONFLICT, "REVIEW_NOT_ALLOWED");

        long delivered = deliveredItem(productId);
        write(customerId, delivered, 5, "좋아요").andExpect(status().isCreated());   // 대조군 — 같은 상품 · 배송 완료 · 일반 주문
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product_reviews WHERE customer_id = ?", Long.class, customerId))
                .as("거절된 셋은 남지 않았다").isEqualTo(1L);
    }

    @Test
    @DisplayName("같은 주문상품에 두 번 쓰면 409 REVIEW_ALREADY_WRITTEN — DB UNIQUE 가 판정한다")
    void secondReviewForSameOrderItemIsConflict() throws Exception {
        long orderItemId = deliveredItem(productId);
        write(customerId, orderItemId, 5, "처음").andExpect(status().isCreated());
        expectError(write(customerId, orderItemId, 3, "두 번째"), HttpStatus.CONFLICT, "REVIEW_ALREADY_WRITTEN");
        assertThat(jdbcTemplate.queryForObject("SELECT body FROM product_reviews WHERE order_item_id = ?", String.class, orderItemId))
                .isEqualTo("처음");
    }

    @Test
    @DisplayName("order 의 403 · 코드를 읽을 수 없는 404 는 500(없음과 구분), 401 은 401, 5xx · 연결 실패는 503 — 코드가 있는 404 는 ReviewInternalCallsTest")
    void orderFailuresMapToTheirStatus() throws Exception {
        long bareForbidden = nextId();
        given(orders.getOrderItem(bareForbidden)).willThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "f", null, null, null));
        expectError(write(customerId, bareForbidden, 5, "x"), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");

        long noRoute = nextId();
        given(orders.getOrderItem(noRoute)).willThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "nf", null, null, null));
        expectError(write(customerId, noRoute, 5, "x"), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");

        long unauthorized = nextId();
        given(orders.getOrderItem(unauthorized)).willThrow(HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "u", null, null, null));
        expectError(write(customerId, unauthorized, 5, "x"), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");

        long conflict = nextId();
        given(orders.getOrderItem(conflict)).willThrow(HttpClientErrorException.create(HttpStatus.CONFLICT, "c", null, null, null));
        expectError(write(customerId, conflict, 5, "x"), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");

        long down = nextId();
        given(orders.getOrderItem(down)).willThrow(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "d", null, null, null));
        expectError(write(customerId, down, 5, "x"), HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");

        long unreachable = nextId();
        given(orders.getOrderItem(unreachable)).willThrow(new ResourceAccessException("connect timed out"));
        expectError(write(customerId, unreachable, 5, "x"), HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE");
    }

    @Test
    @DisplayName("응답이 계약과 다르면 500 이고 저장하지 않는다 — 물은 것과 다른 주문상품 · 필수 칸이 빈 응답 · catalog 에 없는 상품 · 토큰 주인이 아닌 회원")
    void contractViolationsAreIntegrationErrors() throws Exception {
        long other = nextId();
        given(orders.getOrderItem(eq(other))).willReturn(ApiResponse.ok(new OrderClient.OrderItem(other + 100, 1L, productId, 1L,
                "블랙 / 256GB", "DELIVERED", "BUY_NOW")));
        expectError(write(customerId, other, 5, "x"), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");

        // 빈 칸 — 검사가 없으면 결과가 달라지는 칸들이다: 경로가 비면 일반 주문으로 저장되고, 상태가 비면 409 로 잘못 답한다
        long noSource = nextId();
        given(orders.getOrderItem(eq(noSource))).willReturn(ApiResponse.ok(new OrderClient.OrderItem(noSource, 1L, productId, 1L,
                "블랙 / 256GB", "DELIVERED", null)));
        expectError(write(customerId, noSource, 5, "x"), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        long noStatus = nextId();
        given(orders.getOrderItem(eq(noStatus))).willReturn(ApiResponse.ok(new OrderClient.OrderItem(noStatus, 1L, productId, 1L,
                "블랙 / 256GB", null, "BUY_NOW")));
        expectError(write(customerId, noStatus, 5, "x"), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");

        long unknownProduct = nextId();
        given(orders.getOrderItem(eq(unknownProduct))).willReturn(ApiResponse.ok(new OrderClient.OrderItem(unknownProduct, 1L,
                Long.MAX_VALUE, 1L, "블랙 / 256GB", "DELIVERED", "BUY_NOW")));
        expectError(write(customerId, unknownProduct, 5, "x"), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");

        long delivered = deliveredItem(productId);
        given(members.getMe()).willReturn(ApiResponse.ok(new MemberClient.Customer(customerId + 1, "철수랑", "김철수")));
        expectError(write(customerId, delivered, 5, "x"), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product_reviews WHERE customer_id = ?", Long.class, customerId))
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
            "{ \"orderItemId\": 1, \"rating\": 0, \"body\": \"x\" }|rating",
            "{ \"orderItemId\": 1, \"rating\": 6, \"body\": \"x\" }|rating",
            "{ \"orderItemId\": 1, \"rating\": 4.5, \"body\": \"x\" }|rating",
            "{ \"orderItemId\": 1, \"rating\": \"다섯\", \"body\": \"x\" }|rating",
            "{ \"orderItemId\": 1, \"rating\": 5, \"body\": \"   \" }|body",
            "{ \"rating\": 5, \"body\": \"x\" }|orderItemId",
            "{ \"orderItemId\": 1, \"rating\": 5, \"body\": \"x\", \"photo\": \"a\" }|photo"
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
        long orderItemId = deliveredItem(productId);
        mockMvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"orderItemId\": %d, \"rating\": \"5\", \"body\": \"x\" }".formatted(orderItemId))
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
        long orderItemId = deliveredItem(productId);
        long reviewId = data(write(customerId, orderItemId, 5, "처음").andExpect(status().isCreated())).get("reviewId").asLong();

        JsonNode rated = data(revise(customerId, reviewId, "{ \"rating\": 3 }").andExpect(status().isOk()));
        assertThat(rated.get("rating").asInt()).isEqualTo(3);
        assertThat(rated.get("body").asString()).as("보내지 않은 칸은 그대로").isEqualTo("처음");
        JsonNode rewritten = data(revise(customerId, reviewId, "{ \"body\": \" 고쳤어요 \" }").andExpect(status().isOk()));
        assertThat(rewritten.get("body").asString()).isEqualTo("고쳤어요");
        assertThat(rewritten.get("rating").asInt()).isEqualTo(3);

        long stranger = nextId();
        expectError(revise(stranger, reviewId, "{ \"rating\": 1 }"), HttpStatus.NOT_FOUND, "NOT_FOUND");
        expectError(mockMvc.perform(delete("/api/v1/reviews/{id}", reviewId).with(AccessTokens.customer(stranger))),
                HttpStatus.NOT_FOUND, "NOT_FOUND");
        expectValidation(revise(customerId, reviewId, "{}"), "body");

        mockMvc.perform(delete("/api/v1/reviews/{id}", reviewId).with(AccessTokens.customer(customerId)))
                .andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product_reviews WHERE id = ?", Long.class, reviewId)).isZero();
        write(customerId, orderItemId, 4, "다시").andExpect(status().isCreated());   // 지운 뒤에는 같은 주문상품에 다시 쓸 수 있다
    }

    @Test
    @DisplayName("상품 리뷰는 비로그인도 본다 — 최신순 · 페이징, 공개 목록에는 주문상품 id 가 없다. 보이지 않는 상품은 상세처럼 404")
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

        jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = ?", productId);
        mockMvc.perform(get("/api/v1/products/{id}/reviews", productId)).andExpect(status().isNotFound());
        // 상품 상세와 같은 규칙 — 재고 행이 없는(준비 전) 일반 상품 · 없는 상품도 404
        Long notReady = fixtures.product("IN_STOCK", "ACTIVE");
        fixtures.option(notReady, "ACTIVE", new BigDecimal("1000"));
        mockMvc.perform(get("/api/v1/products/{id}/reviews", notReady)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/products/{id}/reviews", Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("모아보기는 비로그인도 — 상위 카테고리는 하위 포함, 다른 카테고리 · 비공개 상품의 리뷰는 빠진다")
    void visibleReviewsFilterByCategory() throws Exception {
        Long parent = fixtures.category();
        Long child = fixtures.childCategory(parent, "삼성");
        Long inParent = readyInStock(parent);
        Long inChild = readyInStock(child);
        Long elsewhere = readyInStock(fixtures.category());
        Long hidden = readyInStock(child);
        write(customerId, deliveredItem(inParent), 5, "상위").andExpect(status().isCreated());
        write(customerId, deliveredItem(inChild), 5, "하위").andExpect(status().isCreated());
        write(customerId, deliveredItem(elsewhere), 5, "다른 곳").andExpect(status().isCreated());
        write(customerId, deliveredItem(hidden), 5, "숨김").andExpect(status().isCreated());
        jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = ?", hidden);

        JsonNode byParent = data(mockMvc.perform(get("/api/v1/reviews").param("categoryId", parent.toString())).andExpect(status().isOk()));
        assertThat(bodies(byParent)).containsExactly("하위", "상위");
        JsonNode byChild = data(mockMvc.perform(get("/api/v1/reviews").param("categoryId", child.toString())).andExpect(status().isOk()));
        assertThat(bodies(byChild)).containsExactly("하위");
        JsonNode all = data(mockMvc.perform(get("/api/v1/reviews").param("size", "100")).andExpect(status().isOk()));
        assertThat(bodies(all)).contains("상위", "하위", "다른 곳").doesNotContain("숨김");
    }

    @Test
    @DisplayName("내 리뷰는 로그인한 회원만 — orderItemId 로 그 주문상품의 리뷰를 고른다(주문 내역의 리뷰 쓰기 / 보기)")
    void myReviewsByOrderItem() throws Exception {
        long written = deliveredItem(productId);
        long notWritten = deliveredItem(productId);
        write(customerId, written, 5, "내 리뷰").andExpect(status().isCreated());

        JsonNode mine = data(mockMvc.perform(get("/api/v1/reviews/mine").param("orderItemId", Long.toString(written))
                .with(AccessTokens.customer(customerId))).andExpect(status().isOk()));
        assertThat(mine.get("total").asLong()).isEqualTo(1);
        assertThat(mine.get("items").get(0).get("orderItemId").asLong()).isEqualTo(written);
        JsonNode none = data(mockMvc.perform(get("/api/v1/reviews/mine").param("orderItemId", Long.toString(notWritten))
                .with(AccessTokens.customer(customerId))).andExpect(status().isOk()));
        assertThat(none.get("total").asLong()).isZero();
        JsonNode others = data(mockMvc.perform(get("/api/v1/reviews/mine").with(AccessTokens.customer(nextId()))).andExpect(status().isOk()));
        assertThat(others.get("total").asLong()).as("남의 리뷰는 내 리뷰가 아니다").isZero();

        jdbcTemplate.update("UPDATE products SET visible = 0 WHERE id = ?", productId);
        JsonNode afterHidden = data(mockMvc.perform(get("/api/v1/reviews/mine").with(AccessTokens.customer(customerId))).andExpect(status().isOk()));
        assertThat(afterHidden.get("total").asLong()).as("내 리뷰는 상품이 비공개가 돼도 보인다").isEqualTo(1);
    }

    @Test
    @DisplayName("쓰기 · 내 리뷰는 회원만 — 익명 401, 관리자 403. 상품 리뷰 · 모아보기는 익명도 200")
    void accessRules() throws Exception {
        String body = "{ \"orderItemId\": 1, \"rating\": 5, \"body\": \"x\" }";
        mockMvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON).content(body).with(AccessTokens.admin()))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/reviews/1").contentType(MediaType.APPLICATION_JSON).content("{ \"rating\": 1 }"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/reviews/1")).andExpect(status().isUnauthorized());
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

    // ── 도우미 ──────────────────────────────────────────────────────────────

    /** 공개 · 판매 중 · 재고 행이 있는(준비된) 일반 상품 — 상품 상세가 보여 주는 상품. */
    private Long readyInStock(Long categoryId) {
        Long product = fixtures.product(categoryId, "IN_STOCK", "ACTIVE");
        fixtures.inventory(fixtures.option(product, "ACTIVE", new BigDecimal("1000")), 5, 0, 0);
        return product;
    }

    private long deliveredItem(Long product) {
        long orderItemId = nextId();
        stubItem(orderItemId, product, "DELIVERED", "BUY_NOW");
        return orderItemId;
    }

    private void stubItem(long orderItemId, Long product, String orderStatus, String source) {
        given(orders.getOrderItem(eq(orderItemId))).willReturn(ApiResponse.ok(new OrderClient.OrderItem(orderItemId, orderItemId + 1,
                product, orderItemId + 2, "블랙 / 256GB", orderStatus, source)));
    }

    private static long nextId() {
        return IDS.incrementAndGet();
    }

    private ResultActions write(long customer, long orderItemId, int rating, String body) throws Exception {
        String json = JSON.writeValueAsString(java.util.Map.of("orderItemId", orderItemId, "rating", rating, "body", body));
        return mockMvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON).content(json)
                .with(AccessTokens.customer(customer)));
    }

    private ResultActions revise(long customer, long reviewId, String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/reviews/{id}", reviewId).contentType(MediaType.APPLICATION_JSON).content(body)
                .with(AccessTokens.customer(customer)));
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
